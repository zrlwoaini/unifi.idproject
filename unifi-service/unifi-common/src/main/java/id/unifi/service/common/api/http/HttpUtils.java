package id.unifi.service.common.api.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import static com.google.common.net.HttpHeaders.ACCEPT;
import static com.google.common.net.HttpHeaders.AUTHORIZATION;
import static com.google.common.net.HttpHeaders.CONTENT_TYPE;
import static com.google.common.net.HttpHeaders.IF_NONE_MATCH;
import com.google.common.net.MediaType;
import id.unifi.service.common.api.Channel;
import id.unifi.service.common.api.Protocol;
import id.unifi.service.common.api.errors.HttpMarshallableError;
import id.unifi.service.common.api.errors.NotAcceptable;
import id.unifi.service.common.api.errors.UnsupportedMediaType;
import id.unifi.service.common.security.Token;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Arrays.stream;
import static javax.servlet.http.HttpServletResponse.SC_INTERNAL_SERVER_ERROR;
import org.eclipse.jetty.util.MultiMap;
import org.eclipse.jetty.util.UrlEncoded;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.AsyncContext;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class HttpUtils {
    private static final Logger log = LoggerFactory.getLogger(HttpUtils.class);
    private static final Pattern commaSplitter = Pattern.compile("\\s*,\\s*");
    private static final Pattern slashSplitter = Pattern.compile("/");
    private static final Pattern authorizationPattern = Pattern.compile("[Ss]ession[Tt]oken\\s+([a-zA-z0-9+/=]+)");
    private static final Base64.Decoder base64 = Base64.getDecoder();
    private static final String SESSION_TOKEN_QUERY_STRING_KEY = "_sessionToken";

    private interface IORunnable {
        void run() throws IOException;
    }

    private HttpUtils() {}

    // Doesn't do full content type negotiation but should be good enough
    // Response and request (if applicable) content types must match,
    // i.e. a JSON request can't be mixed with a msgpack response.
    public static Protocol determineProtocol(List<Protocol> protocols, HttpServletRequest request) {
        var contentTypeHeader = request.getHeader(CONTENT_TYPE);
        var acceptHeader = request.getHeader(ACCEPT);

        if (contentTypeHeader != null) {
            var protocol = protocolFromContentType(protocols, contentTypeHeader);
            if (protocol == null) throw new UnsupportedMediaType(contentTypeHeader);
            return protocol;
        }

        if (acceptHeader == null) return protocols.iterator().next(); // First protocol is default

        // No Content-Type header, try to guess from Accept
        var acceptContentTypes = commaSplitter.split(acceptHeader);
        for (var type : acceptContentTypes) {
            try {
                var protocol = protocolFromContentType(protocols, type);
                if (protocol != null) return protocol;
            } catch (IllegalArgumentException ignored) {
            }
        }

        throw new NotAcceptable();
    }

    public static Map<String, String[]> getQueryParameters(String queryString) {
        var queryParamsMultimap = new MultiMap<String>();
        if (queryString != null) UrlEncoded.decodeUtf8To(queryString, queryParamsMultimap);
        return queryParamsMultimap.toStringArrayMap();
    }

    public static List<String> decodePathSegments(String urlEncodedPath) {
        return stream(slashSplitter.split(urlEncodedPath))
                .map(s -> URLDecoder.decode(s, UTF_8))
                .collect(Collectors.toUnmodifiableList());
    }

    private static Protocol protocolFromContentType(List<Protocol> protocols, String contentType) {
        try {
            var mediaType = MediaType.parse(contentType).withParameters("q", Set.of());
            return protocols.stream().filter(p -> p.getMediaType().is(mediaType)).findFirst().orElse(null);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public static Function<String, JsonNode> paramGetter(ObjectMapper mapper,
                                                         Map<String, String> pathParams,
                                                         Map<String, String[]> queryParams,
                                                         JsonNode bodyJson) {
        return name -> {
            var pathParam = pathParams.get(name);

            // Convert value to AST, so params get type-coerced in the same way as protocol payloads.
            // This avoids having to convert strings to various target types manually.
            if (pathParam != null) return mapper.valueToTree(pathParam);

            var bodyParam = bodyJson != null ? bodyJson.get(name) : null;
            if (bodyParam != null) return bodyParam;
            var queryParamValues = queryParams.get(name);
            if (queryParamValues == null || queryParamValues.length == 0) return null;
            return mapper.valueToTree(queryParamValues.length > 1 ? queryParamValues : queryParamValues[0]);
        };
    }

    public static Channel createChannel(Protocol protocol, AsyncContext context) {
        var response = (HttpServletResponse) context.getResponse();
        return new Channel() {
            public void send(ByteBuffer payload) {
                send(() -> {
                    var os = response.getOutputStream();
                    if (payload.hasArray()) {
                        os.write(payload.array(), payload.arrayOffset(), payload.remaining());
                    } else {
                        var payloadBytes = new byte[payload.remaining()];
                        payload.get(payloadBytes);
                        os.write(payloadBytes);
                    }
                    os.close();
                });
            }

            public void send(String payload) {
                response.setCharacterEncoding(UTF_8.displayName());
                send(() -> {
                    var w = response.getWriter();
                    w.write(payload);
                    w.close();
                });
            }

            private void send(IORunnable writer) {
                response.setHeader(CONTENT_TYPE, protocol.getMediaType().toString());
                try {
                    writer.run();
                } catch (IOException e) {
                    log.debug("Failed to send response", e);
                } finally {
                    context.complete();
                }
            }
        };
    }

    public static void respondWithThrowable(Protocol protocol,
                                            ObjectMapper mapper,
                                            HttpServletResponse response,
                                            Throwable e) throws IOException {
        if (e instanceof HttpMarshallableError) {
            var httpError = (HttpMarshallableError) e;
            response.setStatus(httpError.getHttpStatusCode());

            if (mapper != null && protocol != null) {
                response.setHeader(CONTENT_TYPE, protocol.getMediaType().toString());
                response.setStatus(httpError.getHttpStatusCode());
                var os = response.getOutputStream();
                mapper.writeValue(os, httpError);
                os.close();
            } else {
                // The error occurred before the protocol could be determined, send only the short error message
                response.sendError(httpError.getHttpStatusCode(), httpError.getMessage());
            }
        } else {
            log.error("Error while processing HTTP request", e);
            response.sendError(SC_INTERNAL_SERVER_ERROR);
        }
    }

    public static boolean modified(HttpServletRequest request, byte[] eTag) {
        var ifNoneMatchHeader = request.getHeader(IF_NONE_MATCH);
        return ifNoneMatchHeader == null || stream(commaSplitter.split(ifNoneMatchHeader))
                .filter(p -> p.startsWith("\"") && p.endsWith("\"")) // Skip weak ETags and other nonsense
                .map(p -> p.substring(1, p.length() - 1))
                .flatMap(encoded -> decodeBase64(encoded).stream())
                .noneMatch(candidate -> Arrays.equals(candidate, eTag));
    }

    public static Optional<Token> extractAuthToken(HttpServletRequest request) {
        var authorizationHeader = request.getHeader(AUTHORIZATION);
        if (authorizationHeader != null) {
            var matcher = authorizationPattern.matcher(authorizationHeader);
            if (matcher.matches()) return decodeToken(matcher.group(1));
        }

        var sessionTokens = getQueryParameters(request.getQueryString()).get(SESSION_TOKEN_QUERY_STRING_KEY);
        if (sessionTokens != null && sessionTokens.length == 1) {
            return decodeToken(sessionTokens[0]);
        }

        return Optional.empty();
    }

    private static Optional<Token> decodeToken(String token) {
        try {
            return decodeBase64(token).map(Token::new);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private static Optional<byte[]> decodeBase64(String encoded) {
        try {
            return Optional.of(base64.decode(encoded));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
