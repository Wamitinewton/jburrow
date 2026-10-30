package io.jburrow.server.e2e;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

/**
 * The local service of the HTTP end-to-end tests: the behaviours of {@code scripts/http-echo-server.py} on the JDK's
 * HTTP server, so the suite needs no Python, and a few the tests need to see what arrived.
 *
 * <pre>
 *   GET  /hello                          200, "hello\n"
 *   GET  /marker                         200, this service's marker
 *   GET  /bytes/&lt;n&gt;[?chunked=1]          n bytes of {@link TunnelTestSupport#send}'s stream seeded by n
 *   POST /digest                         200, "&lt;length&gt; &lt;sha256&gt;" of the request body, read as it arrives
 *   POST /echo                           200, the request body back
 *   GET  /sse?count=&lt;n&gt;&amp;interval=&lt;ms&gt;    text/event-stream, one event per interval, each flushed
 *   GET  /stall                          content-length 64 MiB, written until the socket blocks
 *   GET  /sleep?ms=&lt;ms&gt;                  200 after a pause
 *   GET  /cookie                         200 with two set-cookie fields carrying the marker and a Domain
 *   *    /status/&lt;code&gt;                   that status with no body
 *   *    /inspect/...                     200, the method, target and fields as they arrived, as text
 * </pre>
 *
 * <p>Every request is recorded with its method, target, fields and, for the endpoints that read it whole, its body.
 */
final class LocalHttpService implements AutoCloseable {

    /** A request as the service received it. */
    record Received(String method, String target, Map<String, List<String>> fields, byte[] body) {}

    static final long STALL_BYTES = 64L * 1024 * 1024;

    private static final int PIECE = 64 * 1024;

    private final HttpServer server;
    private final ExecutorService threads = Executors.newVirtualThreadPerTaskExecutor();
    private final String marker;
    private final List<Received> received = new CopyOnWriteArrayList<>();
    /** When each server-sent event was handed to the connection, as {@link System#nanoTime}. */
    private final List<Long> eventsWritten = new CopyOnWriteArrayList<>();

    private LocalHttpService(String marker) throws IOException {
        this.marker = marker;
        this.server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 1_024);
        server.createContext("/", this::serve);
        server.setExecutor(threads);
        server.start();
    }

    static LocalHttpService start() throws IOException {
        return start("local-http-service");
    }

    /** A service whose {@code /marker} answers {@code marker}, and whose cookies carry it. */
    static LocalHttpService start(String marker) throws IOException {
        return new LocalHttpService(marker);
    }

    int port() {
        return server.getAddress().getPort();
    }

    List<Received> received() {
        return List.copyOf(received);
    }

    List<Long> eventsWritten() {
        return List.copyOf(eventsWritten);
    }

    private void serve(HttpExchange exchange) throws IOException {
        try (exchange) {
            URI uri = exchange.getRequestURI();
            String path = uri.getRawPath();
            Map<String, String> query = query(uri.getRawQuery());
            switch (path) {
                case "/hello" -> {
                    record(exchange, exchange.getRequestBody().readAllBytes());
                    fixed(exchange, 200, "hello\n");
                }
                case "/marker" -> {
                    record(exchange, exchange.getRequestBody().readAllBytes());
                    fixed(exchange, 200, marker);
                }
                case "/digest" -> digest(exchange);
                case "/echo" -> echo(exchange);
                case "/sse" ->
                    events(
                            exchange,
                            Integer.parseInt(query.getOrDefault("count", "5")),
                            Integer.parseInt(query.getOrDefault("interval", "1000")));
                case "/stall" -> stall(exchange);
                case "/sleep" -> {
                    record(exchange, new byte[0]);
                    pause(Duration.ofMillis(Long.parseLong(query.getOrDefault("ms", "1000"))));
                    fixed(exchange, 200, "awake\n");
                }
                case "/cookie" -> {
                    record(exchange, new byte[0]);
                    exchange.getResponseHeaders().add("Set-Cookie", "session=" + marker + "; Domain=localhost; Path=/");
                    exchange.getResponseHeaders().add("Set-Cookie", "theme=dark; Path=/; domain=.localhost; HttpOnly");
                    fixed(exchange, 200, "cookies\n");
                }
                default -> {
                    if (path.startsWith("/bytes/")) {
                        bytes(
                                exchange,
                                Long.parseLong(path.substring("/bytes/".length())),
                                "1".equals(query.get("chunked")));
                    } else if (path.startsWith("/status/")) {
                        record(exchange, exchange.getRequestBody().readAllBytes());
                        exchange.sendResponseHeaders(Integer.parseInt(path.substring("/status/".length())), -1);
                    } else if (path.startsWith("/inspect/") || path.equals("/inspect")) {
                        inspect(exchange);
                    } else {
                        record(exchange, exchange.getRequestBody().readAllBytes());
                        fixed(exchange, 404, "not found\n");
                    }
                }
            }
        } catch (IOException visitorGone) {
            // The tunnel closed the connection, as it does for a request whose visitor left.
        }
    }

    private void record(HttpExchange exchange, byte[] body) {
        Map<String, List<String>> fields = exchange.getRequestHeaders().entrySet().stream()
                .collect(Collectors.toMap(
                        field -> field.getKey().toLowerCase(Locale.ROOT), Map.Entry::getValue, (a, b) -> a));
        received.add(new Received(exchange.getRequestMethod(), target(exchange.getRequestURI()), fields, body));
    }

    private static void fixed(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(UTF_8);
        if (exchange.getRequestMethod().equals("HEAD")) {
            exchange.getResponseHeaders().set("Content-Length", Integer.toString(bytes.length));
            exchange.sendResponseHeaders(status, -1);
            return;
        }
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    private void digest(HttpExchange exchange) throws IOException {
        MessageDigest sha256 = TunnelTestSupport.sha256();
        long length = 0;
        byte[] buffer = new byte[PIECE];
        InputStream in = exchange.getRequestBody();
        for (int read = in.read(buffer); read >= 0; read = in.read(buffer)) {
            sha256.update(buffer, 0, read);
            length += read;
        }
        record(exchange, new byte[0]);
        fixed(exchange, 200, length + " " + HexFormat.of().formatHex(sha256.digest()));
    }

    private void echo(HttpExchange exchange) throws IOException {
        byte[] body = exchange.getRequestBody().readAllBytes();
        record(exchange, body);
        exchange.sendResponseHeaders(200, body.length == 0 ? -1 : body.length);
        exchange.getResponseBody().write(body);
    }

    private void bytes(HttpExchange exchange, long length, boolean chunked) throws IOException {
        record(exchange, new byte[0]);
        exchange.getResponseHeaders().set("Content-Type", "application/octet-stream");
        exchange.sendResponseHeaders(200, chunked ? 0 : length == 0 ? -1 : length);
        try (OutputStream out = exchange.getResponseBody()) {
            TunnelTestSupport.send(out, length, length);
        }
    }

    private void events(HttpExchange exchange, int count, int intervalMillis) throws IOException {
        record(exchange, new byte[0]);
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(200, 0);
        OutputStream out = exchange.getResponseBody();
        for (int index = 0; index < count; index++) {
            if (index > 0) {
                pause(Duration.ofMillis(intervalMillis));
            }
            eventsWritten.add(System.nanoTime());
            out.write(("id: " + index + "\ndata: event " + index + "\n\n").getBytes(UTF_8));
            out.flush();
        }
        out.close();
    }

    private void stall(HttpExchange exchange) throws IOException {
        record(exchange, new byte[0]);
        exchange.sendResponseHeaders(200, STALL_BYTES);
        OutputStream out = exchange.getResponseBody();
        byte[] piece = new byte[PIECE];
        for (long sent = 0; sent < STALL_BYTES; sent += PIECE) {
            out.write(piece);
        }
    }

    private void inspect(HttpExchange exchange) throws IOException {
        byte[] body = exchange.getRequestBody().readAllBytes();
        record(exchange, body);
        StringBuilder text = new StringBuilder()
                .append(exchange.getRequestMethod())
                .append(' ')
                .append(target(exchange.getRequestURI()))
                .append('\n');
        exchange.getRequestHeaders()
                .forEach((name, values) -> values.forEach(value -> text.append(name.toLowerCase(Locale.ROOT))
                        .append(": ")
                        .append(value)
                        .append('\n')));
        fixed(exchange, 200, text.toString());
    }

    /** The request target as it arrived: the raw path and query, never decoded. */
    private static String target(URI uri) {
        return uri.getRawPath() + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery());
    }

    private static Map<String, String> query(String rawQuery) {
        if (rawQuery == null || rawQuery.isEmpty()) {
            return Map.of();
        }
        return Arrays.stream(rawQuery.split("&"))
                .map(pair -> pair.split("=", 2))
                .collect(Collectors.toMap(pair -> pair[0], pair -> pair.length > 1 ? pair[1] : "", (a, b) -> a));
    }

    /** The first value of a recorded request's field {@code name}. */
    static Optional<String> field(Received request, String name) {
        List<String> values = request.fields().get(name);
        return values == null || values.isEmpty() ? Optional.empty() : Optional.of(values.getFirst());
    }

    private static void pause(Duration duration) throws InterruptedIOException {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("interrupted");
        }
    }

    @Override
    public void close() {
        server.stop(0);
        threads.shutdownNow();
    }
}
