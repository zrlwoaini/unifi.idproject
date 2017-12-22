package id.unifi.service.common.api;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.core.Base64Variants;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MappingJsonFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import com.fasterxml.jackson.databind.node.BinaryNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fasterxml.jackson.module.paramnames.ParameterNamesModule;
import id.unifi.service.common.api.errors.InternalServerError;
import id.unifi.service.common.api.errors.InvalidParameterFormat;
import id.unifi.service.common.api.errors.MarshallableError;
import id.unifi.service.common.api.errors.MissingParameter;
import id.unifi.service.common.util.HexEncoded;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.StatusCode;
import org.msgpack.jackson.dataformat.MessagePackFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.function.BiConsumer;
import java.util.function.Function;

public class Dispatcher<S> {
    private static final Logger log = LoggerFactory.getLogger(Dispatcher.class);

    private static final Message.Version CURRENT_PROTOCOL_VERSION = new Message.Version(1, 0, 0);

    private final Map<Protocol, ObjectMapper> objectMappers;
    private final ServiceRegistry serviceRegistry;
    private final Class<S> sessionDataType;
    private final Function<Session, S> sessionDataCreator;
    private final ConcurrentMap<Session, S> sessionDataStore;
    private final Set<SessionListener<S>> sessionListeners;
    private final Map<String, PayloadConsumer> messageListeners;

    public interface PayloadConsumer {
        void accept(ObjectMapper om, Session session, JsonNode node);
    }

    public interface SessionListener<S> {
        void onSessionCreated(Session session, S sessionData);
        void onSessionDropped(Session session);
    }

    public Dispatcher(ServiceRegistry serviceRegistry,
                      Class<S> sessionDataType,
                      Function<Session, S> sessionDataCreator) {
        this.serviceRegistry = serviceRegistry;
        this.sessionDataType = sessionDataType;
        this.sessionDataCreator = sessionDataCreator;
        this.sessionDataStore = new ConcurrentHashMap<>();

        this.objectMappers = Map.of(
                Protocol.JSON, configureObjectMapper(new ObjectMapper(new MappingJsonFactory())),
                Protocol.MSGPACK, configureObjectMapper(new ObjectMapper(new MessagePackFactory()))
        );

        this.sessionListeners = new CopyOnWriteArraySet<>();
        this.messageListeners = new ConcurrentHashMap<>();
    }

    public void dispatch(Session session, MessageStream stream, Protocol protocol, ReturnChannel returnChannel) {
        ObjectMapper mapper = objectMappers.get(protocol);
        Message message = null;
        try {
            message = parseMessage(stream, mapper);

            PayloadConsumer messageListener = messageListeners.get(message.messageType);
            if (messageListener != null) {
                messageListener.accept(mapper, session, message.payload);
                return;
            }

            ServiceRegistry.Operation operation = serviceRegistry.getOperation(message.messageType);
            processRequest(session, returnChannel, mapper, protocol, message, operation);
        } catch (IOException e) {
            throw new RuntimeException(e);
        } catch (MarshallableError e) {
            if (message != null) {
                sendPayload(returnChannel, mapper, protocol, errorMessage(mapper, message, e));
            } else {
                session.close(StatusCode.BAD_PAYLOAD, "Couldn't process payload");
            }
        } catch (RuntimeException e) {
            if (message != null) {
                sendPayload(returnChannel, mapper, protocol, errorMessage(mapper, message, new InternalServerError()));
            } else {
                session.close(StatusCode.BAD_PAYLOAD, "Couldn't process payload");
            }
        }
    }

    public <T> void putMessageListener(String messageType, Type type, BiConsumer<Session, T> listener) {
        messageListeners.put(messageType, (mapper, session, node) -> {
            try {
                T payload = mapper.readValue(mapper.treeAsTokens(node), mapper.constructType(type));
                listener.accept(session, payload);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
    }

    public void createSession(Session session) {
        S sessionData = sessionDataCreator.apply(session);
        sessionDataStore.put(session, sessionData);
        sessionListeners.forEach(l -> l.onSessionCreated(session, sessionData));
    }

    public void dropSession(Session session) {
        sessionDataStore.remove(session);
        sessionListeners.forEach(l -> l.onSessionDropped(session));
    }

    public void addSessionListener(SessionListener<S> listener) {
        sessionListeners.add(listener);
    }

    private void processRequest(Session session,
                                ReturnChannel returnChannel,
                                ObjectMapper mapper,
                                Protocol protocol,
                                Message message,
                                ServiceRegistry.Operation operation) {
        Object[] params = operation.params.entrySet().stream().map(entry -> {
            if (entry.getValue() == Session.class)
                return session;
            if (entry.getValue() == sessionDataType)
                return sessionDataStore.get(session);

            String name = entry.getKey();
            Type type = entry.getValue();
            try {
                JsonNode paramNode = message.payload.get(name);
                if (paramNode == null || paramNode.isNull()) {
                    throw new MissingParameter(name, type.getTypeName());
                }
                return readValue(mapper, type, paramNode);
            } catch (JsonProcessingException e) {
                throw new InvalidParameterFormat(name, e.getMessage());
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }).toArray();

        switch (operation.invocationType) {
            case RPC:
                Message rpcResponse;
                try {
                    Object result = serviceRegistry.invokeRpc(operation, params);

                    JsonNode payload = operation.resultType.equals(Void.TYPE) ? null : mapper.valueToTree(result);
                    rpcResponse = new Message(
                            CURRENT_PROTOCOL_VERSION,
                            message.releaseVersion,
                            message.correlationId,
                            operation.resultMessageType,
                            payload);
                    log.trace("Response message: {}", rpcResponse);
                } catch (MarshallableError e) {
                    rpcResponse = errorMessage(mapper, message, e);
                }

                sendPayload(returnChannel, mapper, protocol, rpcResponse);
                break;

            case MULTI:
                MessageListener<Object> listenerParam = (messageType, payloadObj) -> {
                    JsonNode payloadNode = mapper.valueToTree(payloadObj);
                    Message response = new Message(
                            CURRENT_PROTOCOL_VERSION,
                            message.releaseVersion,
                            message.correlationId,
                            messageType,
                            payloadNode);
                    log.trace("Multi-response message: {}", response);
                    sendPayload(returnChannel, mapper, protocol, response);
                };

                try {
                    serviceRegistry.invokeMulti(operation, params, listenerParam);
                } catch (MarshallableError e) {
                    sendPayload(returnChannel, mapper, protocol, errorMessage(mapper, message, e));
                }
                break;
        }
    }

    private static Object readValue(ObjectMapper mapper, Type type, JsonNode paramNode) throws IOException {
        JavaType javaType = mapper.constructType(type);
        try {
            return mapper.readValue(mapper.treeAsTokens(paramNode), javaType);
        } catch (InvalidFormatException e) {
            // For failed base-64 decoding try again with URL-safe Base64 variant
            // This is a hack that won't work on nested structures
            if (e.getTargetType() == byte[].class && paramNode.isTextual()) {
                byte[] binValue = ((TextNode) paramNode).getBinaryValue(Base64Variants.MODIFIED_FOR_URL);
                return mapper.readValue(mapper.treeAsTokens(new BinaryNode(binValue)), javaType);
            }
            throw e;
        }
    }

    private static Message errorMessage(ObjectMapper mapper, Message message, MarshallableError e) {
        return new Message(
                CURRENT_PROTOCOL_VERSION,
                message.releaseVersion,
                message.correlationId,
                e.getProtocolMessageType(),
                mapper.valueToTree(e));
    }

    private static ObjectMapper configureObjectMapper(ObjectMapper mapper) {
        return mapper.registerModule(new ParameterNamesModule(JsonCreator.Mode.PROPERTIES))
                .registerModule(new Jdk8Module())
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    private static Message parseMessage(MessageStream stream, ObjectMapper mapper) throws IOException {
        JsonNode message = stream.isBinary() ? mapper.readTree(stream.inputStream) : mapper.readTree(stream.reader);
        log.trace("Request parsed: {}", message);
        Message request = mapper.treeToValue(message, Message.class);
        log.trace("Request unmarshalled: {}", request);
        return request;
    }

    private static void sendPayload(ReturnChannel returnChannel,
                                    ObjectMapper mapper,
                                    Protocol protocol,
                                    Message payload) {
        if (protocol.isBinary()) {
            byte[] binaryPayload;
            try {
                binaryPayload = mapper.writeValueAsBytes(payload);
            } catch (JsonProcessingException e) {
                throw new RuntimeException(e);
            }
            if (log.isTraceEnabled()) {
                log.trace("Sending marshalled response: {}", new HexEncoded(binaryPayload));
            }
            returnChannel.send(ByteBuffer.wrap(binaryPayload));
        } else {
            String stringPayload;
            try {
                stringPayload = mapper.writeValueAsString(payload);
            } catch (JsonProcessingException e) {
                throw new RuntimeException(e);
            }
            log.trace("Sending marshalled response: {}", stringPayload);
            returnChannel.send(stringPayload);
        }
    }
}
