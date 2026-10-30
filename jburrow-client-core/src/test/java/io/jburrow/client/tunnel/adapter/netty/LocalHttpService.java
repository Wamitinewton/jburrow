package io.jburrow.client.tunnel.adapter.netty;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.jburrow.client.tunnel.domain.LocalTarget;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * A local web service on the JDK's own HTTP server, so the client is tested against an implementation it shares no code
 * with. Each exchange runs on a virtual thread of its own, so one that blocks holds back no other.
 *
 * <pre>
 * GET  /hello                 200, "hello", by length
 * GET  /chunked               200, "hello, chunked", chunked
 * GET  /download?bytes=N&seed=S  200, N random bytes from seed S, by length
 * HEAD /hello                 200, no body
 * GET  /no-content            204
 * GET  /not-modified          304
 * POST /echo                  200, the request body, by length
 * POST /echo-chunked          200, the request body, chunked
 * POST /upload                200, "&lt;bytes&gt; &lt;sha-256&gt;" of the request body
 * POST /stall                 reads nothing until {@link #releaseStall()}, then as /upload
 * POST /reject                413 at once, without reading the body
 * </pre>
 */
final class LocalHttpService implements AutoCloseable {

    /** A multiple of 4, so each piece of {@link #randomBody} takes whole ints from its {@link Random}. */
    static final int CHUNK = 64 * 1024;

    private final ExecutorService threads = Executors.newVirtualThreadPerTaskExecutor();
    private final CountDownLatch stall = new CountDownLatch(1);
    private final CountDownLatch stalled = new CountDownLatch(1);
    private final List<String> requestHosts = new CopyOnWriteArrayList<>();
    private final HttpServer server;

    LocalHttpService() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.setExecutor(threads);
        server.createContext("/", this::handle);
        server.start();
    }

    LocalTarget target() {
        return LocalTarget.of(
                InetAddress.getLoopbackAddress().getHostAddress(),
                server.getAddress().getPort());
    }

    /** The {@code host} of each request, in arrival order. */
    List<String> requestHosts() {
        return requestHosts;
    }

    /** Waits until a {@code /stall} request has arrived and is holding back its body. */
    void awaitStalled() throws InterruptedException {
        stalled.await();
    }

    void releaseStall() {
        stall.countDown();
    }

    @Override
    public void close() {
        stall.countDown();
        server.stop(0);
        threads.shutdownNow();
    }

    /**
     * Calls {@code chunk} with {@code length} pseudo-random bytes from {@code seed}, in pieces of 64 KiB and a last
     * shorter one: the same bytes, in the same pieces, for the same arguments.
     */
    static void randomBody(long length, long seed, ChunkSink chunk) throws IOException {
        Random random = new Random(seed);
        byte[] buffer = new byte[CHUNK];
        for (long left = length; left > 0; left -= CHUNK) {
            random.nextBytes(buffer);
            chunk.accept(buffer, (int) Math.min(CHUNK, left));
        }
    }

    /** The SHA-256 of {@link #randomBody}, in hex. */
    static String randomSha256(long length, long seed) throws IOException {
        MessageDigest digest = sha256();
        randomBody(length, seed, (bytes, count) -> digest.update(bytes, 0, count));
        return HexFormat.of().formatHex(digest.digest());
    }

    static String sha256Hex(byte[] bytes) {
        return HexFormat.of().formatHex(sha256().digest(bytes));
    }

    /** Takes the first {@code length} bytes of {@code bytes}, which it must not keep. */
    @FunctionalInterface
    interface ChunkSink {
        void accept(byte[] bytes, int length) throws IOException;
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            requestHosts.add(exchange.getRequestHeaders().getFirst("Host"));
            String path = exchange.getRequestURI().getPath();
            switch (path) {
                case "/hello" -> {
                    if (exchange.getRequestMethod().equals("HEAD")) {
                        exchange.sendResponseHeaders(200, -1);
                    } else {
                        respond(exchange, 200, "hello".getBytes(StandardCharsets.US_ASCII), false);
                    }
                }
                case "/chunked" -> respond(exchange, 200, "hello, chunked".getBytes(StandardCharsets.US_ASCII), true);
                case "/download" -> download(exchange);
                case "/no-content" -> exchange.sendResponseHeaders(204, -1);
                case "/not-modified" -> exchange.sendResponseHeaders(304, -1);
                case "/echo" -> respond(exchange, 200, exchange.getRequestBody().readAllBytes(), false);
                case "/echo-chunked" ->
                    respond(exchange, 200, exchange.getRequestBody().readAllBytes(), true);
                case "/upload" -> upload(exchange);
                case "/stall" -> {
                    stalled.countDown();
                    try {
                        stall.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    upload(exchange);
                }
                case "/reject" -> exchange.sendResponseHeaders(413, -1);
                default -> exchange.sendResponseHeaders(404, -1);
            }
        }
    }

    private static void respond(HttpExchange exchange, int status, byte[] body, boolean chunked) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "text/plain");
        exchange.sendResponseHeaders(status, chunked ? 0 : body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    private static void download(HttpExchange exchange) throws IOException {
        Map<String, String> query = query(exchange.getRequestURI());
        long length = Long.parseLong(query.get("bytes"));
        exchange.sendResponseHeaders(200, length);
        try (OutputStream out = exchange.getResponseBody()) {
            randomBody(length, Long.parseLong(query.get("seed")), (bytes, count) -> out.write(bytes, 0, count));
        }
    }

    private static void upload(HttpExchange exchange) throws IOException {
        MessageDigest digest = sha256();
        long count = 0;
        byte[] buffer = new byte[CHUNK];
        try (InputStream in = exchange.getRequestBody()) {
            for (int read = in.read(buffer); read != -1; read = in.read(buffer)) {
                digest.update(buffer, 0, read);
                count += read;
            }
        }
        String answer = count + " " + HexFormat.of().formatHex(digest.digest());
        respond(exchange, 200, answer.getBytes(StandardCharsets.US_ASCII), false);
    }

    private static Map<String, String> query(URI uri) {
        Map<String, String> values = new HashMap<>();
        for (String pair : uri.getRawQuery().split("&")) {
            String[] nameAndValue = pair.split("=", 2);
            values.put(nameAndValue[0], nameAndValue[1]);
        }
        return values;
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
