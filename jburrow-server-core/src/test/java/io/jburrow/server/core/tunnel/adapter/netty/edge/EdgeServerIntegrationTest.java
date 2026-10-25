package io.jburrow.server.core.tunnel.adapter.netty.edge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import io.jburrow.protocol.adapter.jackson.ControlJson;
import io.jburrow.protocol.control.UpgradeRefusal;
import io.jburrow.protocol.testsupport.ScriptedPeer;
import io.jburrow.protocol.wire.ErrorCode;
import io.jburrow.protocol.wire.ProtocolVersion;
import io.jburrow.server.core.portpool.application.PortReservationService;
import io.jburrow.server.core.portpool.domain.PortPool;
import io.jburrow.server.core.portpool.domain.PortRange;
import io.jburrow.server.core.tunnel.application.InMemoryAuthtokenVerifier;
import io.jburrow.server.core.tunnel.application.RecordingTunnelMetrics;
import io.jburrow.server.core.tunnel.application.RegisterTunnels;
import io.jburrow.server.core.tunnel.application.RegistrationPolicy;
import io.jburrow.server.core.tunnel.application.SessionAdmission;
import io.netty.handler.codec.http.websocketx.WebSocketCloseStatus;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class EdgeServerIntegrationTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(1);
    /** The production bound: a connection still open after the listener closes gets 5 seconds before it is closed. */
    private static final Duration STOP_TIMEOUT = Duration.ofSeconds(15);

    private static final int MAX_HANDSHAKING = 2;
    private static final ControlJson JSON = ControlJson.create();

    private final SessionAdmission admission = new SessionAdmission(8, MAX_HANDSHAKING, 3);
    private final RecordingTunnelMetrics metrics = new RecordingTunnelMetrics();
    private final ExecutorService blocking = Executors.newVirtualThreadPerTaskExecutor();
    private final List<ScriptedPeer> peers = new ArrayList<>();
    private final List<Upgrade> upgrades = new CopyOnWriteArrayList<>();
    private EdgeServer edge;

    /** What the stub initializer was handed. */
    private record Upgrade(String subprotocol, InetAddress clientAddress) {}

    @AfterEach
    void stop() throws Exception {
        peers.forEach(ScriptedPeer::close);
        if (edge != null) {
            edge.stop(STOP_TIMEOUT).get(20, TimeUnit.SECONDS);
        }
        blocking.shutdownNow();
    }

    @Test
    void aValidUpgradeSelectsTheCurrentDraftAndReachesTheSession() {
        start(holdingSessions());

        connect(ProtocolVersion.CURRENT);

        awaitTrue(() -> upgrades.size() == 1);
        assertThat(upgrades.get(0).subprotocol()).isEqualTo("jburrow.v1-draft.1");
        assertThat(upgrades.get(0).clientAddress()).isEqualTo(InetAddress.getLoopbackAddress());
        assertThat(admission.handshaking()).isOne();
    }

    @Test
    void theStubInitializerClosesWith1011AndTheTicketIsReleased() {
        start(SessionInitializer.unavailable());

        ScriptedPeer peer = connect(ProtocolVersion.CURRENT);

        assertThat(peer.expectClosed(TIMEOUT)).isEqualTo(WebSocketCloseStatus.INTERNAL_SERVER_ERROR);
        awaitTrue(() -> admission.activeSessions() == 0 && admission.handshaking() == 0);
    }

    @Test
    void anOriginHeaderIsForbidden() throws IOException {
        start(holdingSessions());

        Response response = exchange(upgradeRequest(ProtocolVersion.CURRENT, "Origin: https://evil.example\r\n"));

        assertThat(response.status()).isEqualTo(403);
        assertThat(response.refusal().code()).isEqualTo(ErrorCode.FORBIDDEN_ORIGIN);
        assertThat(metrics.events()).contains("handshakeFailed FORBIDDEN_ORIGIN");
    }

    @Test
    void anOldDraftIsRefusedWithTheSupportedList() throws IOException {
        start(holdingSessions());

        Response response = exchange(upgradeRequest("jburrow.v1-draft.0", ""));

        assertThat(response.status()).isEqualTo(400);
        UpgradeRefusal refusal = response.refusal();
        assertThat(refusal.code()).isEqualTo(ErrorCode.UNSUPPORTED_PROTOCOL_VERSION);
        assertThat(refusal.supported()).containsExactly("jburrow.v1-draft.1");
    }

    @Test
    void anOfferedExtensionIsNeverSelected() throws IOException {
        start(holdingSessions());

        try (Socket socket = socket()) {
            write(socket, upgradeRequest(ProtocolVersion.CURRENT, "Sec-WebSocket-Extensions: permessage-deflate\r\n"));
            Map<String, String> head = readHead(socket.getInputStream());

            assertThat(head.get(":status")).isEqualTo("101");
            assertThat(head.get("sec-websocket-protocol")).isEqualTo(ProtocolVersion.CURRENT);
            assertThat(head).doesNotContainKey("sec-websocket-extensions");
        }
    }

    @Test
    void aRequestHeadTrickledPastTheRequestTimeoutIsClosed() throws Exception {
        start(holdingSessions());
        byte[] request = upgradeRequest(ProtocolVersion.CURRENT, "").getBytes(StandardCharsets.US_ASCII);
        long sendNanos = REQUEST_TIMEOUT.multipliedBy(2).toNanos() / request.length;

        try (Socket socket = socket()) {
            OutputStream out = socket.getOutputStream();
            long started = System.nanoTime();
            assertThatExceptionOfType(IOException.class).isThrownBy(() -> {
                for (byte b : request) {
                    out.write(b);
                    out.flush();
                    TimeUnit.NANOSECONDS.sleep(sendNanos);
                }
                if (socket.getInputStream().read() == -1) {
                    throw new SocketException("closed without a response");
                }
            });
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isGreaterThanOrEqualTo(REQUEST_TIMEOUT);
        }
        assertThat(upgrades).isEmpty();
        awaitTrue(() -> admission.activeSessions() == 0);
    }

    @Test
    void oneUpgradePastTheHandshakeCapIsRefused() throws IOException {
        start(holdingSessions());
        for (int i = 0; i < MAX_HANDSHAKING; i++) {
            connect(ProtocolVersion.CURRENT);
        }
        awaitTrue(() -> upgrades.size() == MAX_HANDSHAKING);

        Response response = exchange(upgradeRequest(ProtocolVersion.CURRENT, ""));

        assertThat(response.status()).isEqualTo(503);
        assertThat(response.headers().get("retry-after")).isEqualTo("5");
        assertThat(response.refusal().code()).isEqualTo(ErrorCode.SERVER_AT_CAPACITY);
        assertThat(metrics.events()).containsOnlyOnce("handshakeFailed SERVER_AT_CAPACITY");
    }

    @Test
    void afterBeginShutdownAnUpgradeInProgressIsRefusedAndNewConnectionsAreNot() throws Exception {
        start(holdingSessions());
        int port = edge.boundPort();

        try (Socket early = socket()) {
            edge.beginShutdown();
            awaitTrue(() -> !edge.isAccepting());
            write(early, upgradeRequest(ProtocolVersion.CURRENT, ""));
            Response response = read(early.getInputStream());

            assertThat(response.status()).isEqualTo(503);
            assertThat(response.headers().get("retry-after")).isEqualTo("5");
            assertThat(response.refusal().code()).isEqualTo(ErrorCode.SERVER_SHUTTING_DOWN);
        }
        awaitTrue(() -> refusesConnections(port));
    }

    @Test
    void stopCompletesAndFreesThePort() throws Exception {
        start(holdingSessions());
        ScriptedPeer peer = connect(ProtocolVersion.CURRENT);
        awaitTrue(() -> upgrades.size() == 1);
        int port = edge.boundPort();

        edge.stop(STOP_TIMEOUT).get(20, TimeUnit.SECONDS);

        assertThat(edge.isAccepting()).isFalse();
        assertThat(peer.expectClosed(TIMEOUT)).isNotNull();
        try (ServerSocket rebound = new ServerSocket()) {
            rebound.setReuseAddress(true);
            rebound.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), port));
            assertThat(rebound.isBound()).isTrue();
        }
        awaitTrue(() -> admission.activeSessions() == 0);
        assertThat(edge.stop(STOP_TIMEOUT)).isCompleted();
    }

    @Test
    void startingTwiceIsHarmlessAndAPortInUseFailsStartup() throws IOException {
        start(holdingSessions());
        edge.start();

        try (ServerSocket taken = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            EdgeServer second = new EdgeServer(settings(taken.getLocalPort()), dependencies(), holdingSessions());
            assertThatIllegalStateException().isThrownBy(second::start).withMessageContaining("Could not bind");
        }
    }

    @Test
    void reportsItsTransportAndDirectMemory() {
        start(holdingSessions());

        assertThat(edge.transportName()).isIn("epoll", "nio");
        assertThat(edge.usedDirectMemory()).isNotNegative();
    }

    private void start(SessionInitializer sessions) {
        edge = new EdgeServer(settings(0), dependencies(), sessions);
        edge.start();
        assertThat(edge.isAccepting()).isTrue();
    }

    private SessionInitializer holdingSessions() {
        return (channel, subprotocol, clientAddress, ticket) -> upgrades.add(new Upgrade(subprotocol, clientAddress));
    }

    private static EdgeSettings settings(int port) {
        return new EdgeSettings(
                new InetSocketAddress(InetAddress.getLoopbackAddress(), port),
                "localhost",
                EdgeTransport.AUTO,
                2,
                List.of(Cidr.parse("127.0.0.1/32"), Cidr.parse("::1/128")),
                8,
                MAX_HANDSHAKING,
                REQUEST_TIMEOUT,
                Duration.ofSeconds(10),
                Duration.ofSeconds(15),
                Duration.ofSeconds(45),
                128 * 1024,
                512 * 1024,
                32 * 1024,
                128 * 1024,
                "test");
    }

    private EngineDependencies dependencies() {
        PortReservationService ports = new PortReservationService(new PortPool(
                PortRange.parse("40000-40009"),
                Duration.ofSeconds(60),
                Duration.ofMinutes(10),
                new Random(1),
                InstantSource.system()));
        RegisterTunnels register = new RegisterTunnels(
                new InMemoryAuthtokenVerifier(), admission, ports, new RegistrationPolicy(1, "localhost"));
        return new EngineDependencies(
                register, admission, ports, metrics, blocking, new TcpSettings("127.0.0.1", "localhost"));
    }

    private ScriptedPeer connect(String subprotocol) {
        ScriptedPeer peer = ScriptedPeer.connect(
                URI.create("ws://localhost:" + edge.boundPort() + ScriptedPeer.CONNECT_PATH), subprotocol);
        peers.add(peer);
        return peer;
    }

    private Socket socket() throws IOException {
        Socket socket = new Socket(InetAddress.getLoopbackAddress(), edge.boundPort());
        socket.setSoTimeout((int) TIMEOUT.toMillis());
        return socket;
    }

    private static boolean refusesConnections(int port) {
        try (Socket ignored = new Socket(InetAddress.getLoopbackAddress(), port)) {
            return false;
        } catch (ConnectException refused) {
            return true;
        } catch (IOException other) {
            return false;
        }
    }

    private String upgradeRequest(String subprotocol, String extraHeaders) {
        return "GET /v1/connect HTTP/1.1\r\n"
                + "Host: localhost:" + edge.boundPort() + "\r\n"
                + "Upgrade: websocket\r\n"
                + "Connection: Upgrade\r\n"
                + "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n"
                + "Sec-WebSocket-Version: 13\r\n"
                + "Sec-WebSocket-Protocol: " + subprotocol + "\r\n"
                + extraHeaders
                + "\r\n";
    }

    private Response exchange(String request) throws IOException {
        try (Socket socket = socket()) {
            write(socket, request);
            return read(socket.getInputStream());
        }
    }

    private static void write(Socket socket, String request) throws IOException {
        socket.getOutputStream().write(request.getBytes(StandardCharsets.US_ASCII));
        socket.getOutputStream().flush();
    }

    /** A refused upgrade: the head, then the body until the server closes the connection. */
    private static Response read(InputStream in) throws IOException {
        Map<String, String> head = readHead(in);
        byte[] body = in.readAllBytes();
        assertThat(body).hasSize(Integer.parseInt(head.get("content-length")));
        return new Response(Integer.parseInt(head.get(":status")), head, body);
    }

    /** The status line and headers, names lower-cased, with the status code under {@code :status}. */
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

    private record Response(int status, Map<String, String> headers, byte[] body) {
        UpgradeRefusal refusal() {
            return JSON.readRefusal(body);
        }
    }

    private static void awaitTrue(BooleanSupplier condition) {
        long deadline = System.nanoTime() + TIMEOUT.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("Condition not met within " + TIMEOUT);
            }
            CompletableFuture.runAsync(() -> {}, CompletableFuture.delayedExecutor(20, TimeUnit.MILLISECONDS))
                    .join();
        }
    }
}
