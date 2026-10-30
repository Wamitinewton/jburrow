package io.jburrow.server.core.tunnel.adapter.netty.edge;

import static org.assertj.core.api.Assertions.assertThat;

import io.jburrow.protocol.control.Windows;
import io.jburrow.server.core.portpool.application.PortReservationService;
import io.jburrow.server.core.portpool.domain.PortPool;
import io.jburrow.server.core.portpool.domain.PortRange;
import io.jburrow.server.core.routing.application.SubdomainRoutes;
import io.jburrow.server.core.tunnel.application.InMemoryAuthtokenVerifier;
import io.jburrow.server.core.tunnel.application.RecordingTunnelMetrics;
import io.jburrow.server.core.tunnel.application.RegisterTunnels;
import io.jburrow.server.core.tunnel.application.RegistrationFixtures;
import io.jburrow.server.core.tunnel.application.SessionAdmission;
import io.jburrow.server.core.tunnel.application.StreamQuota;
import io.jburrow.server.core.tunnel.domain.Principal;
import io.jburrow.server.core.tunnel.domain.TunnelId;
import io.netty.channel.Channel;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.InstantSource;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/**
 * A real edge on loopback, connect host {@code localhost} and HTTP domain {@code localhost}, whose session
 * initializer only records each upgraded channel. Speaks HTTP/1.1 through plain sockets, so a test controls every
 * byte on the connection.
 */
final class RealEdge implements AutoCloseable {

    static final Duration TIMEOUT = Duration.ofSeconds(5);
    static final int SOCKET_BUFFER = 16 * 1024;

    final SessionAdmission admission = new SessionAdmission(8, 4, 3);
    final List<Channel> upgraded = new CopyOnWriteArrayList<>();
    private final ExecutorService blocking = Executors.newVirtualThreadPerTaskExecutor();
    final EdgeServer server;

    RealEdge(EdgeTransport transport) {
        server = new EdgeServer(
                settings(transport),
                dependencies(),
                (channel, subprotocol, clientAddress, ticket) -> upgraded.add(channel));
        server.start();
    }

    private static EdgeSettings settings(EdgeTransport transport) {
        return new EdgeSettings(
                new InetSocketAddress(InetAddress.getLoopbackAddress(), 0),
                "localhost",
                transport,
                2,
                List.of(),
                8,
                4,
                Duration.ofSeconds(10),
                Duration.ofSeconds(10),
                Duration.ofSeconds(15),
                Duration.ofSeconds(45),
                128 * 1024,
                512 * 1024,
                new Windows(256 * 1024, 512 * 1024),
                1,
                8,
                "test",
                new HttpSettings(
                        "localhost",
                        1_024,
                        Duration.ofSeconds(60),
                        Duration.ofSeconds(75),
                        SOCKET_BUFFER,
                        Duration.ofSeconds(1),
                        16L * 1024 * 1024,
                        2L * 1024 * 1024),
                Set.of());
    }

    private EngineDependencies dependencies() {
        PortReservationService ports = new PortReservationService(new PortPool(
                PortRange.parse("40000-40009"),
                Duration.ofSeconds(60),
                Duration.ofMinutes(10),
                new Random(1),
                InstantSource.system()));
        SubdomainRoutes<Principal, TunnelId> routes = RegistrationFixtures.routes();
        RegisterTunnels register = new RegisterTunnels(
                new InMemoryAuthtokenVerifier(), admission, ports, routes, RegistrationFixtures.policy(1, "localhost"));
        return new EngineDependencies(
                register,
                admission,
                new StreamQuota(8),
                ports,
                routes,
                new RecordingTunnelMetrics(),
                blocking,
                new TcpSettings("127.0.0.1", "localhost", 32 * 1024));
    }

    Socket socket() throws IOException {
        Socket socket = new Socket(InetAddress.getLoopbackAddress(), server.boundPort());
        socket.setSoTimeout((int) TIMEOUT.toMillis());
        return socket;
    }

    /** The server's side of the one connection accepted so far. */
    Channel onlyConnection() {
        awaitTrue(() -> server.connections().size() == 1);
        return server.connections().iterator().next();
    }

    String upgradeRequest() {
        return "GET /v1/connect HTTP/1.1\r\n"
                + "Host: localhost:" + server.boundPort() + "\r\n"
                + "Upgrade: websocket\r\n"
                + "Connection: Upgrade\r\n"
                + "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n"
                + "Sec-WebSocket-Version: 13\r\n"
                + "Sec-WebSocket-Protocol: jburrow.v1-draft.2\r\n"
                + "\r\n";
    }

    String get(String host) {
        return "GET / HTTP/1.1\r\nHost: " + host + ":" + server.boundPort() + "\r\n\r\n";
    }

    static void write(Socket socket, String request) throws IOException {
        socket.getOutputStream().write(request.getBytes(StandardCharsets.US_ASCII));
        socket.getOutputStream().flush();
    }

    /** One response: its head, then a body of its {@code content-length}, or none for a {@code 101}. */
    static Response read(InputStream in) throws IOException {
        Map<String, String> head = readHead(in);
        int length = Integer.parseInt(head.getOrDefault("content-length", "0"));
        byte[] body = in.readNBytes(length);
        assertThat(body).hasSize(length);
        return new Response(Integer.parseInt(head.get(":status")), head, new String(body, StandardCharsets.UTF_8));
    }

    private static Map<String, String> readHead(InputStream in) throws IOException {
        ByteArrayOutputStream head = new ByteArrayOutputStream();
        while (!head.toString(StandardCharsets.US_ASCII).endsWith("\r\n\r\n")) {
            int b = in.read();
            if (b < 0) {
                throw new SocketException("closed inside the response head");
            }
            head.write(b);
        }
        String[] lines = head.toString(StandardCharsets.US_ASCII).split("\r\n");
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put(":status", lines[0].split(" ")[1]);
        for (int i = 1; i < lines.length; i++) {
            int colon = lines[i].indexOf(':');
            headers.put(
                    lines[i].substring(0, colon).toLowerCase(Locale.ROOT),
                    lines[i].substring(colon + 1).strip());
        }
        return headers;
    }

    record Response(int status, Map<String, String> headers, String body) {
        String header(String name) {
            return headers.get(name);
        }
    }

    static void awaitTrue(BooleanSupplier condition) {
        long deadline = System.nanoTime() + TIMEOUT.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("Condition not met within " + TIMEOUT);
            }
            CompletableFuture.runAsync(() -> {}, CompletableFuture.delayedExecutor(20, TimeUnit.MILLISECONDS))
                    .join();
        }
    }

    @Override
    public void close() throws Exception {
        server.stop(Duration.ofSeconds(15)).get(20, TimeUnit.SECONDS);
        blocking.shutdownNow();
    }
}
