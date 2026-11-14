package in.railops.testsupport;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Consumer-driven HTTP contracts without a broker: {@code contracts/http/<consumer>-to-<provider>.<name>.json}
 * holds one interaction. The CONSUMER test points its real client at {@link #stubProvider} and checks what it sent;
 * the PROVIDER test replays the request into its real controller and checks the response shape. Both sides break
 * the build on drift; neither needs the other running.
 */
public final class HttpContract {

    private static final ObjectMapper M = new JsonMapper();

    public final JsonNode json;

    private HttpContract(JsonNode json) {
        this.json = json;
    }

    public static HttpContract load(String name) {
        Path dir = EventContracts.root().getParent().resolve("http");
        try {
            return new HttpContract(M.readTree(Files.readString(dir.resolve(name + ".json"))));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public JsonNode request() {
        return json.get("request");
    }

    public JsonNode response() {
        return json.get("response");
    }

    /** What the stub provider recorded. */
    public static final class Recorded {
        public volatile String method;
        public volatile String path;
        public final Map<String, String> headers = new ConcurrentHashMap<>();
        public volatile String body;
    }

    /** Starts a provider stub on a free port that records the request and answers with the contract's response. */
    public HttpServer stubProvider(Recorded into) {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", ex -> {
                into.method = ex.getRequestMethod();
                into.path = ex.getRequestURI().getPath();
                ex.getRequestHeaders().forEach((k, v) -> into.headers.put(k.toLowerCase(), String.join(",", v)));
                into.body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                byte[] out = M.writeValueAsBytes(response().get("body"));
                ex.getResponseHeaders().add("Content-Type", "application/json");
                ex.sendResponseHeaders(response().get("status").asInt(), out.length);
                ex.getResponseBody().write(out);
                ex.close();
            });
            server.start();
            return server;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Same field names, and same JSON node types, recursively (values may differ). Numbers compare by kind only
     * (2915 vs 2915.00 are both fine on the wire); a string where a number is expected is drift.
     */
    public static void assertSameShape(JsonNode expected, JsonNode actual, String at) {
        if (expected.isObject()) {
            if (!actual.isObject()) {
                throw new AssertionError(at + ": expected object, got " + actual.getNodeType());
            }
            Iterator<String> names = expected.propertyNames().iterator();
            while (names.hasNext()) {
                String f = names.next();
                if (!actual.has(f)) {
                    throw new AssertionError(at + "." + f + ": missing (got fields " + iter(actual.propertyNames().iterator()) + ")");
                }
                assertSameShape(expected.get(f), actual.get(f), at + "." + f);
            }
            Iterator<String> extra = actual.propertyNames().iterator();
            while (extra.hasNext()) {
                String f = extra.next();
                if (!expected.has(f)) {
                    throw new AssertionError(at + "." + f + ": not in the contract");
                }
            }
        } else if (expected.isNumber() != actual.isNumber() || expected.isString() != actual.isString()
                || expected.isBoolean() != actual.isBoolean()) {
            throw new AssertionError(at + ": expected " + expected.getNodeType() + ", got " + actual.getNodeType());
        }
    }

    private static String iter(Iterator<String> it) {
        StringBuilder sb = new StringBuilder();
        it.forEachRemaining(s -> sb.append(s).append(' '));
        return sb.toString().trim();
    }
}
