package id.unifi.service.common.api;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.function.Function.identity;
import static java.util.stream.Collectors.toUnmodifiableMap;
import org.eclipse.jetty.websocket.api.RemoteEndpoint;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.StatusCode;
import org.eclipse.jetty.websocket.api.WebSocketException;
import org.eclipse.jetty.websocket.api.WriteCallback;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketClose;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketConnect;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketMessage;
import org.eclipse.jetty.websocket.api.annotations.WebSocket;
import org.eclipse.jetty.websocket.servlet.ServletUpgradeRequest;
import org.eclipse.jetty.websocket.servlet.ServletUpgradeResponse;
import org.eclipse.jetty.websocket.servlet.WebSocketCreator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import javax.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.ByteBuffer;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

@WebSocket
public class WebSocketDelegate {
    private static final Logger log = LoggerFactory.getLogger(WebSocketDelegate.class);

    private static final long PING_INTERVAL_MILLIS = 30_000;

    private final Dispatcher dispatcher;
    private final Protocol protocol;
    private final CountDownLatch closeLatch;
    private Thread pingThread;
    private volatile int closeCode;
    private volatile long nextPingMillis;

    public WebSocketDelegate(Dispatcher<?> dispatcher, Protocol protocol) {
        this.dispatcher = dispatcher;
        this.protocol = protocol;
        this.closeLatch = new CountDownLatch(1);
    }

    public static class Creator implements WebSocketCreator {
        private final Dispatcher dispatcher;
        private final Map<String, Protocol> protocolByPath;

        Creator(Dispatcher<?> dispatcher, String basePath, Collection<Protocol> protocols) {
            this.dispatcher = dispatcher;
            this.protocolByPath = protocols.stream().collect(toUnmodifiableMap(p -> basePath + "/" + p, identity()));
        }

        public WebSocketDelegate createWebSocket(ServletUpgradeRequest req, ServletUpgradeResponse res) {
            var path = req.getHttpServletRequest().getPathInfo();
            var protocol = protocolByPath.get(path);
            if (protocol != null) {
                return new WebSocketDelegate(dispatcher, protocol);
            } else {
                res.setStatusCode(HttpServletResponse.SC_NOT_FOUND);
                return null;
            }
        }
    }

    public int awaitClose() throws InterruptedException {
        closeLatch.await();
        return closeCode;
    }

    @OnWebSocketConnect
    public void onConnect(Session session) {
        log.trace("Connected: {}", session);
        pingThread = new Thread(() -> pingLoop(session));
        pingThread.start();

        dispatcher.createSession(session);
    }

    private void pingLoop(Session session) {
        var payload = ByteBuffer.allocate(0);
        updatePingTime();
        while (true) {
            var nowMillis = System.currentTimeMillis();
            if (nextPingMillis <= nowMillis) {
                try {
                    log.trace("Sending ping in {}", session);
                    session.getRemote().sendPing(payload);
                    updatePingTime();
                } catch (IOException | WebSocketException e) {
                    log.trace("Can't send ping in {}", session, e);
                    return;
                }
            } else {
                try {
                    var waitMillis = nextPingMillis - nowMillis;
                    log.trace("Waiting {} ms ", waitMillis);
                    Thread.sleep(waitMillis);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }
    }

    @OnWebSocketClose
    public void onClose(Session session, int code, String reason) {
        log.trace("Closed ({} {}): {}", code, reason, session);
        dispatcher.dropSession(session);
        if (pingThread != null) pingThread.interrupt();

        closeCode = code;
        closeLatch.countDown();
    }

    @OnWebSocketMessage
    public void onTextMessage(Session session, Reader reader) {
        updatePingTime();
        log.trace("Received text message in {}", session);
        if (protocol.isBinary()) {
            session.close(StatusCode.BAD_DATA, "Text message not supported by binary protocol " + protocol);
        } else {
            dispatchMessage(session, new MessageStream(reader));
        }
    }

    @OnWebSocketMessage
    public void onBinaryMessage(Session session, InputStream stream) {
        updatePingTime();
        var messageStream = protocol.isBinary()
                ? new MessageStream(stream)
                : new MessageStream(new BufferedReader(new InputStreamReader(stream, UTF_8)));
        dispatchMessage(session, messageStream);
    }

    private void dispatchMessage(Session session, MessageStream messageStream) {
        Channel returnChannel = new Channel() {
            public void send(ByteBuffer payload) {
                send(payload, null);
            }

            public void send(String payload) {
                send(null, payload);
            }

            private void send(@Nullable ByteBuffer bytePayload, @Nullable String stringPayload) {
                RemoteEndpoint remote;
                try {
                    remote = session.getRemote();
                } catch (WebSocketException e) {
                    log.debug("Remote session closed, ignoring message send: {}", session);
                    return;
                }

                WriteCallback writeCallback = new WriteCallback() {
                    public void writeFailed(Throwable t) {
                        log.debug("Write failed for {} in {}", protocol, session, t);
                    }

                    public void writeSuccess() {
                        log.trace("Write succeeded for {} in {}", protocol, session);
                    }
                };

                log.trace("Sending {} message to {}", protocol, session);
                updatePingTime();
                if (bytePayload != null) {
                    remote.sendBytes(bytePayload, writeCallback);
                } else {
                    remote.sendString(stringPayload, writeCallback);
                }
            }
        };

        log.trace("Dispatching incoming {} message in {}", protocol, session);
        dispatcher.dispatch(session, messageStream, protocol, returnChannel);
    }

    private void updatePingTime() {
        nextPingMillis = System.currentTimeMillis() + PING_INTERVAL_MILLIS;
    }
}
