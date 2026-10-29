package io.jburrow.server.core.tunnel.adapter.netty.control;

import static org.assertj.core.api.Assertions.assertThat;

import io.jburrow.protocol.adapter.netty.Frame;
import io.jburrow.protocol.control.ClientInfo;
import io.jburrow.protocol.control.ErrorBody;
import io.jburrow.protocol.control.Hello;
import io.jburrow.protocol.control.Register;
import io.jburrow.protocol.control.RegisteredTunnel;
import io.jburrow.protocol.control.ServerHello;
import io.jburrow.protocol.control.SessionLimits;
import io.jburrow.protocol.control.TunnelKind;
import io.jburrow.protocol.control.TunnelRequest;
import io.jburrow.protocol.control.Windows;
import io.jburrow.protocol.testsupport.ConformanceVectors;
import io.jburrow.protocol.testsupport.Hex;
import io.jburrow.protocol.testsupport.ScriptedPeer;
import io.jburrow.protocol.wire.ErrorCode;
import io.jburrow.protocol.wire.FrameLimits;
import io.jburrow.protocol.wire.ProtocolException;
import io.jburrow.protocol.wire.ProtocolVersion;
import io.jburrow.server.core.portpool.application.PortReservationService;
import io.jburrow.server.core.portpool.domain.PortPool;
import io.jburrow.server.core.portpool.domain.PortRange;
import io.jburrow.server.core.tunnel.adapter.netty.edge.Cidr;
import io.jburrow.server.core.tunnel.adapter.netty.edge.EdgeServer;
import io.jburrow.server.core.tunnel.adapter.netty.edge.EdgeSettings;
import io.jburrow.server.core.tunnel.adapter.netty.edge.EdgeTransport;
import io.jburrow.server.core.tunnel.adapter.netty.edge.EngineDependencies;
import io.jburrow.server.core.tunnel.adapter.netty.edge.TcpSettings;
import io.jburrow.server.core.tunnel.application.InMemoryAuthtokenVerifier;
import io.jburrow.server.core.tunnel.application.RecordingTunnelMetrics;
import io.jburrow.server.core.tunnel.application.RegisterTunnels;
import io.jburrow.server.core.tunnel.application.RegistrationPolicy;
import io.jburrow.server.core.tunnel.application.SessionAdmission;
import io.jburrow.server.core.tunnel.application.StreamQuota;
import io.jburrow.server.core.tunnel.application.port.AuthtokenVerifier;
import io.jburrow.server.core.tunnel.domain.Principal;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.websocketx.WebSocketCloseStatus;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JsonNode;

class ControlSessionIntegrationTest {

    private static final String TOKEN = "jb_" + "a".repeat(43);
    private static final String PRINCIPAL = "tester";
    private static final String PUBLIC_HOST = "localhost";
    private static final int FIRST_PORT = 40_100;
    private static final int POOL_SIZE = 5;

    /** The shortest intervals the protocol lets a server announce. */
    private static final Duration HANDSHAKE_TIMEOUT = Duration.ofSeconds(1);

    private static final Duration PING_INTERVAL = Duration.ofSeconds(1);
    private static final Duration IDLE_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration TIMEOUT = Duration.ofSeconds(5);
    /** Not the defaults, so the test proves the server announces its settings rather than constants. */
    private static final Windows SERVER_WINDOWS = new Windows(131_072, 393_216);

    private static final int MAX_CONCURRENT_STREAMS = 7;
    private static final Duration STOP_TIMEOUT = Duration.ofSeconds(15);

    private final InMemoryAuthtokenVerifier tokens = new InMemoryAuthtokenVerifier().with(TOKEN, PRINCIPAL);
    private final SlowVerifier verifier = new SlowVerifier(tokens);
    private final SessionAdmission admission = new SessionAdmission(16, 8, 3);
    private final PortReservationService ports = new PortReservationService(new PortPool(
            PortRange.parse(FIRST_PORT + "-" + (FIRST_PORT + POOL_SIZE - 1)),
            Duration.ofSeconds(60),
            Duration.ofMinutes(10),
            new Random(1),
            InstantSource.system()));
    private final RecordingTunnelMetrics metrics = new RecordingTunnelMetrics();
    private final ExecutorService blocking = Executors.newVirtualThreadPerTaskExecutor();
    private final FakeTunnelListeners listeners = new FakeTunnelListeners(PUBLIC_HOST);
    private final List<ScriptedPeer> peers = new ArrayList<>();

    private EdgeServer edge;
    private ControlSessionInitializer sessions;

    @AfterEach
    void stop() throws Exception {
        peers.forEach(ScriptedPeer::close);
        if (edge != null) {
            edge.stop(STOP_TIMEOUT).get(20, TimeUnit.SECONDS);
        }
        blocking.shutdownNow();
    }

    @Test
    void aPipelinedHandshakeIsAnsweredWithHelloThenTheRegisterReply() {
        start();
        ScriptedPeer peer = connect();

        peer.send(hello());
        peer.send(register(TOKEN, tcp("default", null)));

        ServerHello hello = peer.expect(Frame.ServerHello.class, TIMEOUT).message();
        assertThat(hello.sessionId()).isNotNull();
        assertThat(hello.server().name()).isEqualTo("jburrow-server");
        assertThat(hello.capabilities()).isEmpty();
        assertThat(hello.limits())
                .isEqualTo(new SessionLimits(
                        FrameLimits.MAX_DATA_PAYLOAD,
                        1,
                        MAX_CONCURRENT_STREAMS,
                        PING_INTERVAL.toMillis(),
                        IDLE_TIMEOUT.toMillis()));
        assertThat(hello.windows()).isEqualTo(SERVER_WINDOWS);
        RegisteredTunnel tunnel =
                peer.expect(Frame.Registered.class, TIMEOUT).message().tunnels().getFirst();
        assertThat(tunnel.ref()).isEqualTo("default");
        assertThat(tunnel.kind()).isEqualTo(TunnelKind.TCP);
        assertThat(tunnel.publicPort()).isBetween(FIRST_PORT, FIRST_PORT + POOL_SIZE - 1);
        assertThat(tunnel.url()).isEqualTo("tcp://" + PUBLIC_HOST + ":" + tunnel.publicPort());
        assertThat(ports.snapshot().reserved()).isOne();
        assertThat(admission.sessionsOf(new Principal(PRINCIPAL))).isOne();
        awaitTrue(() -> metrics.events().contains("handshakeEstablished"));
        assertThat(metrics.events()).contains("sessionStarted", "registerSucceeded");
        assertThat(sessions.registry().active()).isOne();
    }

    @Test
    void anUnpipelinedHandshakeWorksTheSame() throws InterruptedException {
        start();
        ScriptedPeer peer = connect();

        peer.send(hello());
        peer.expect(Frame.ServerHello.class, TIMEOUT);
        TimeUnit.MILLISECONDS.sleep(200);
        peer.send(register(TOKEN, tcp("default", FIRST_PORT + 2)));

        RegisteredTunnel tunnel =
                peer.expect(Frame.Registered.class, TIMEOUT).message().tunnels().getFirst();
        assertThat(tunnel.publicPort()).isEqualTo(FIRST_PORT + 2);
    }

    @Test
    void registerAsTheFirstFrameIsAProtocolError() {
        start();
        ScriptedPeer peer = connect();

        peer.send(register(TOKEN, tcp("default", null)));

        assertThat(peer.expect(Frame.Error.class, TIMEOUT).body().code()).isEqualTo(ErrorCode.PROTOCOL_ERROR);
        assertThat(peer.expectClosed(TIMEOUT)).isEqualTo(WebSocketCloseStatus.PROTOCOL_ERROR);
        assertThat(verifier.calls()).isZero();
    }

    @Test
    void aSecondHelloIsAProtocolErrorAndTheRegisterBehindItIsNeverActedOn() {
        start();
        ScriptedPeer peer = connect();

        peer.send(hello());
        peer.send(hello());
        peer.send(register(TOKEN, tcp("default", null)));

        peer.expect(Frame.ServerHello.class, TIMEOUT);
        peer.expectError(ErrorCode.PROTOCOL_ERROR, TIMEOUT);
        assertThat(verifier.calls()).isZero();
    }

    @Test
    void dataBeforeRegistrationIsAProtocolError() {
        start();
        ScriptedPeer peer = helloed(connect());

        peer.send(new Frame.Data(1, Unpooled.copiedBuffer("hi", StandardCharsets.US_ASCII)));

        peer.expectError(ErrorCode.PROTOCOL_ERROR, TIMEOUT);
    }

    @Test
    void dataOnAStreamThatWasNeverOpenedIsAProtocolError() {
        start();
        ScriptedPeer peer = established(connect());

        peer.send(new Frame.Data(1, Unpooled.copiedBuffer("hi", StandardCharsets.US_ASCII)));

        peer.expectError(ErrorCode.PROTOCOL_ERROR, TIMEOUT);
        awaitTrue(() -> metrics.events().contains("sessionEnded PROTOCOL_ERROR"));
    }

    @Test
    void windowUpdateIsAProtocolErrorInThisDraft() {
        start();
        ScriptedPeer peer = established(connect());

        peer.send(new Frame.WindowUpdate(1, 1_024));

        peer.expectError(ErrorCode.PROTOCOL_ERROR, TIMEOUT);
    }

    @Test
    void aTextMessageIsAProtocolError() {
        start();
        ScriptedPeer peer = connect();

        peer.sendText("{\"client\":{}}");

        peer.expectError(ErrorCode.PROTOCOL_ERROR, TIMEOUT);
    }

    static Stream<JsonNode> serverVectors() {
        return ConformanceVectors.invalid().stream()
                .filter(vector -> vector.get("role").asString().equals("server"));
    }

    /**
     * A message longer than the WebSocket layer accepts is refused there, with status 1009 and no {@code ERROR}, as
     * {@code docs/protocol.md} §3.6 allows.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("serverVectors")
    void aBrokenMessageEndsTheSessionWithTheVectorsCode(JsonNode vector) {
        start();
        ScriptedPeer peer = connect();
        String hex = vector.get("hex").asString();

        peer.sendRaw(hex);

        if (Hex.decode(hex).length > FrameLimits.MAX_MESSAGE_LENGTH) {
            assertThat(peer.expectClosed(TIMEOUT)).isEqualTo(WebSocketCloseStatus.MESSAGE_TOO_BIG);
        } else {
            peer.expectError(ErrorCode.valueOf(vector.get("error").asString()), TIMEOUT);
        }
    }

    @Test
    void aBadAuthtokenIsRefusedWithPolicyViolationAndTakesNoPort() {
        start();
        ScriptedPeer peer = helloed(connect());

        peer.send(register("jb_" + "b".repeat(43), tcp("default", null)));

        ErrorBody error = peer.expect(Frame.Error.class, TIMEOUT).body();
        assertThat(error.code()).isEqualTo(ErrorCode.AUTHTOKEN_INVALID);
        assertThat(error.message()).doesNotContain("jb_");
        assertThat(peer.expectClosed(TIMEOUT)).isEqualTo(WebSocketCloseStatus.POLICY_VIOLATION);
        assertThat(ports.snapshot().free()).isEqualTo(POOL_SIZE);
        awaitTrue(() -> metrics.events().contains("registerFailed AUTHTOKEN_INVALID"));
        assertThat(metrics.events()).contains("handshakeFailed AUTHTOKEN_INVALID");
    }

    @Test
    void moreTunnelsThanTheLimitAreRefused() {
        start();
        ScriptedPeer peer = helloed(connect());

        peer.send(register(TOKEN, tcp("one", null), tcp("two", null)));

        peer.expectError(ErrorCode.TUNNEL_LIMIT_EXCEEDED, TIMEOUT);
        assertThat(verifier.calls()).isZero();
    }

    @Test
    void aRequestedPortAnotherSessionHoldsIsUnavailableAndNamesTheTunnel() {
        start();
        established(connect(), tcp("default", FIRST_PORT));
        ScriptedPeer second = helloed(connect());

        second.send(register(TOKEN, tcp("ssh", FIRST_PORT)));

        ErrorBody error = second.expectError(ErrorCode.PORT_UNAVAILABLE, TIMEOUT);
        assertThat(error.ref()).isEqualTo("ssh");
        awaitTrue(() -> ports.snapshot().reserved() == 1);
    }

    @Test
    void aPrincipalAtItsSessionLimitIsRefusedUntilOneOfItsSessionsCloses() {
        start();
        List<ScriptedPeer> open = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            open.add(established(connect()));
        }
        ScriptedPeer fourth = helloed(connect());

        fourth.send(register(TOKEN, tcp("default", null)));
        fourth.expectError(ErrorCode.SESSION_LIMIT_EXCEEDED, TIMEOUT);
        open.getFirst().close();
        awaitTrue(() -> admission.sessionsOf(new Principal(PRINCIPAL)) == 2);

        established(connect());
        assertThat(admission.sessionsOf(new Principal(PRINCIPAL))).isEqualTo(3);
    }

    @Test
    void aClientThatSendsNothingTimesOutTheHandshake() {
        start();
        long started = System.nanoTime();
        ScriptedPeer peer = connect();

        ErrorBody error = peer.expect(Frame.Error.class, TIMEOUT).body();

        assertThat(error.code()).isEqualTo(ErrorCode.HANDSHAKE_TIMEOUT);
        assertThat(elapsedSince(started))
                .isBetween(HANDSHAKE_TIMEOUT.minusMillis(100), HANDSHAKE_TIMEOUT.plusSeconds(1));
        assertThat(peer.expectClosed(TIMEOUT)).isEqualTo(WebSocketCloseStatus.POLICY_VIOLATION);
        awaitTrue(() -> admission.activeSessions() == 0 && sessions.registry().active() == 0);
    }

    @Test
    void helloWithoutRegisterTimesOutTheHandshake() {
        start();
        ScriptedPeer peer = helloed(connect());

        peer.expectError(ErrorCode.HANDSHAKE_TIMEOUT, TIMEOUT);
    }

    @Test
    void theHandshakeDeadlineCoversASlowVerifierAndItsReservationIsGivenBack() {
        verifier.delay(HANDSHAKE_TIMEOUT.plusMillis(500));
        start();
        ScriptedPeer peer = helloed(connect());

        peer.send(register(TOKEN, tcp("default", null)));

        peer.expectError(ErrorCode.HANDSHAKE_TIMEOUT, TIMEOUT);
        awaitTrue(() -> verifier.calls() == 1 && verifier.inFlight() == 0);
        awaitTrue(() -> ports.snapshot().free() == POOL_SIZE);
        awaitTrue(() -> admission.activeSessions() == 0);
    }

    @Test
    void aClientThatLeavesDuringVerificationLeavesNothingBehind() {
        verifier.delay(Duration.ofMillis(700));
        start();
        ScriptedPeer peer = helloed(connect());

        peer.send(register(TOKEN, tcp("default", null)));
        awaitTrue(() -> verifier.inFlight() == 1);
        peer.close();

        awaitTrue(() -> sessions.registry().active() == 0);
        awaitTrue(() -> verifier.calls() == 1 && verifier.inFlight() == 0);
        awaitTrue(() -> ports.snapshot().free() == POOL_SIZE);
        assertThat(admission.activeSessions()).isZero();
        assertThat(admission.sessionsOf(new Principal(PRINCIPAL))).isZero();
        assertThat(listeners.binds()).isZero();
    }

    @Test
    void aSilentClientIsPingedThenTimedOut() {
        start();
        ScriptedPeer peer = established(connect());
        long started = System.nanoTime();

        peer.silence();

        peer.expect(Frame.Ping.class, TIMEOUT);
        assertThat(elapsedSince(started)).isBetween(PING_INTERVAL.minusMillis(200), PING_INTERVAL.plusMillis(700));
        peer.expectError(ErrorCode.HEARTBEAT_TIMEOUT, TIMEOUT);
        assertThat(elapsedSince(started)).isBetween(IDLE_TIMEOUT.minusMillis(200), IDLE_TIMEOUT.plusSeconds(1));
        awaitTrue(() -> ports.snapshot().free() == POOL_SIZE);
    }

    @Test
    void aClientThatAnswersPingsStaysConnected() throws InterruptedException {
        start();
        ScriptedPeer peer = established(connect());

        TimeUnit.MILLISECONDS.sleep(IDLE_TIMEOUT.multipliedBy(2).plusSeconds(1).toMillis());

        assertThat(sessions.registry().active()).isOne();
        assertThat(metrics.events()).noneMatch(event -> event.startsWith("sessionEnded"));
        peer.silence();
        peer.expect(Frame.Ping.class, TIMEOUT);
    }

    @Test
    void aBurstOfPingsIsRateLimited() {
        start();
        ScriptedPeer peer = established(connect());

        int sent = 0;
        try {
            for (; sent < 20; sent++) {
                peer.send(new Frame.Ping(sent));
            }
        } catch (IllegalStateException closedByTheServer) {
            // The session ended inside the burst, so the rest of it can't be written.
        }

        assertThat(sent).isGreaterThan(5);
        peer.expectError(ErrorCode.PING_RATE_EXCEEDED, TIMEOUT);
    }

    @Test
    void anErrorFromTheClientClosesTheSessionWithoutAReply() {
        start();
        ScriptedPeer peer = established(connect());

        peer.send(Frame.Error.of(ErrorCode.INTERNAL_ERROR, "client\nbroke"));

        assertThat(peer.expectClosed(TIMEOUT)).isNotNull();
        awaitTrue(() -> metrics.events().contains("sessionEnded CLIENT_CLOSED"));
        assertThat(metrics.events()).contains("protocolError INTERNAL_ERROR REMOTE");
        assertThat(metrics.events()).doesNotContain("protocolError INTERNAL_ERROR LOCAL");
    }

    @Test
    void aNormalWebSocketCloseIsRecordedAsClientClosed() {
        start();
        ScriptedPeer peer = established(connect());

        peer.close();

        awaitTrue(() -> metrics.events().contains("sessionEnded CLIENT_CLOSED"));
        awaitTrue(() -> ports.snapshot().free() == POOL_SIZE && admission.activeSessions() == 0);
        assertThat(listeners.unbinds()).isOne();
    }

    @Test
    void aFailedBindRefusesTheRegistrationAndGivesThePortBack() {
        listeners.failBindsWith(ProtocolException.session(ErrorCode.PORT_UNAVAILABLE, "Port in use.")
                .forTunnel("default"));
        start();
        ScriptedPeer peer = helloed(connect());

        peer.send(register(TOKEN, tcp("default", null)));

        assertThat(peer.expectError(ErrorCode.PORT_UNAVAILABLE, TIMEOUT).ref()).isEqualTo("default");
        awaitTrue(() -> ports.snapshot().free() == POOL_SIZE);
    }

    @Test
    void withoutTunnelListenersRegistrationIsAnInternalError() {
        start(TunnelListeners.unavailable());
        ScriptedPeer peer = helloed(connect());

        peer.send(register(TOKEN, tcp("default", null)));

        assertThat(peer.expect(Frame.Error.class, TIMEOUT).body().code()).isEqualTo(ErrorCode.INTERNAL_ERROR);
        assertThat(peer.expectClosed(TIMEOUT)).isEqualTo(WebSocketCloseStatus.INTERNAL_SERVER_ERROR);
        awaitTrue(() -> ports.snapshot().free() == POOL_SIZE && admission.activeSessions() == 0);
    }

    @Test
    void stoppingTheEdgeEndsEverySessionAndGivesEverythingBack() throws Exception {
        start();
        List<ScriptedPeer> open = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            open.add(established(connect()));
        }

        edge.stop(STOP_TIMEOUT).get(20, TimeUnit.SECONDS);

        for (ScriptedPeer peer : open) {
            assertThat(peer.expect(Frame.Error.class, TIMEOUT).body().code()).isEqualTo(ErrorCode.SERVER_SHUTTING_DOWN);
            assertThat(peer.expectClosed(TIMEOUT)).isEqualTo(WebSocketCloseStatus.ENDPOINT_UNAVAILABLE);
        }
        awaitTrue(() -> ports.snapshot().free() == POOL_SIZE);
        assertThat(admission.activeSessions()).isZero();
        assertThat(admission.handshaking()).isZero();
        assertThat(sessions.registry().active()).isZero();
        assertThat(metrics.events().stream().filter("sessionEnded SERVER_SHUTTING_DOWN"::equals))
                .hasSize(3);
    }

    @Test
    void aClientThatStopsReadingIsCutOffAfterTheForceCloseTimeout() {
        start();
        ScriptedPeer peer = established(connect());
        peer.pauseReading();
        long started = System.nanoTime();

        peer.send(hello());

        awaitTrue(() -> sessions.registry().active() == 0);
        assertThat(elapsedSince(started))
                .isBetween(
                        SessionCloser.FORCE_CLOSE_TIMEOUT.minusMillis(200),
                        SessionCloser.FORCE_CLOSE_TIMEOUT.plusSeconds(1));
        peer.resumeReading();
        peer.expectError(ErrorCode.PROTOCOL_ERROR, TIMEOUT);
    }

    private void start() {
        start(listeners);
    }

    private void start(TunnelListeners tunnelListeners) {
        EdgeSettings settings = settings();
        RegisterTunnels register =
                new RegisterTunnels(verifier, admission, ports, new RegistrationPolicy(1, PUBLIC_HOST));
        EngineDependencies deps = new EngineDependencies(
                register,
                admission,
                new StreamQuota(16),
                ports,
                metrics,
                blocking,
                new TcpSettings("127.0.0.1", PUBLIC_HOST, 32 * 1024));
        sessions = new ControlSessionInitializer(settings, deps, tunnelListeners);
        edge = new EdgeServer(settings, deps, sessions);
        edge.start();
    }

    private static EdgeSettings settings() {
        return new EdgeSettings(
                new InetSocketAddress(InetAddress.getLoopbackAddress(), 0),
                "localhost",
                EdgeTransport.AUTO,
                2,
                List.of(Cidr.parse("127.0.0.1/32"), Cidr.parse("::1/128")),
                16,
                8,
                Duration.ofSeconds(5),
                HANDSHAKE_TIMEOUT,
                PING_INTERVAL,
                IDLE_TIMEOUT,
                128 * 1024,
                512 * 1024,
                SERVER_WINDOWS,
                MAX_CONCURRENT_STREAMS,
                16,
                "test");
    }

    private ScriptedPeer connect() {
        ScriptedPeer peer = ScriptedPeer.connect(
                URI.create("ws://localhost:" + edge.boundPort() + ScriptedPeer.CONNECT_PATH), ProtocolVersion.CURRENT);
        peers.add(peer);
        return peer;
    }

    private static ScriptedPeer helloed(ScriptedPeer peer) {
        peer.send(hello());
        peer.expect(Frame.ServerHello.class, TIMEOUT);
        return peer;
    }

    private static ScriptedPeer established(ScriptedPeer peer) {
        return established(peer, tcp("default", null));
    }

    private static ScriptedPeer established(ScriptedPeer peer, TunnelRequest tunnel) {
        helloed(peer).send(register(TOKEN, tunnel));
        peer.expect(Frame.Registered.class, TIMEOUT);
        return peer;
    }

    private static Frame hello() {
        return new Frame.Hello(new Hello(
                new ClientInfo("jburrow-test", "1.0", "linux", "amd64"), List.of(), ScriptedPeer.CLIENT_WINDOWS));
    }

    private static Frame register(String authtoken, TunnelRequest... tunnels) {
        return new Frame.Register(new Register(authtoken, List.of(tunnels)));
    }

    private static TunnelRequest tcp(String ref, Integer remotePort) {
        return new TunnelRequest(ref, TunnelKind.TCP, remotePort);
    }

    private static Duration elapsedSince(long startedNanos) {
        return Duration.ofNanos(System.nanoTime() - startedNanos);
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

    /** Delays every verification, as a database-backed verifier might, and counts verifications in progress. */
    private static final class SlowVerifier implements AuthtokenVerifier {

        private final InMemoryAuthtokenVerifier delegate;
        private final AtomicInteger inFlight = new AtomicInteger();
        private volatile Duration delay = Duration.ZERO;

        SlowVerifier(InMemoryAuthtokenVerifier delegate) {
            this.delegate = delegate;
        }

        void delay(Duration delay) {
            this.delay = delay;
        }

        @Override
        public Optional<String> verify(String authtoken) {
            inFlight.incrementAndGet();
            try {
                TimeUnit.NANOSECONDS.sleep(delay.toNanos());
                return delegate.verify(authtoken);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while verifying", e);
            } finally {
                inFlight.decrementAndGet();
            }
        }

        int calls() {
            return delegate.calls();
        }

        int inFlight() {
            return inFlight.get();
        }
    }
}
