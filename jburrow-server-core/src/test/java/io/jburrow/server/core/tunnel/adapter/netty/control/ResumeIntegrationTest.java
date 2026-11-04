package io.jburrow.server.core.tunnel.adapter.netty.control;

import static org.assertj.core.api.Assertions.assertThat;

import io.jburrow.protocol.adapter.netty.Frame;
import io.jburrow.protocol.control.ClientInfo;
import io.jburrow.protocol.control.Hello;
import io.jburrow.protocol.control.Register;
import io.jburrow.protocol.control.Registered;
import io.jburrow.protocol.control.RegisteredTunnel;
import io.jburrow.protocol.control.ResumeToken;
import io.jburrow.protocol.control.TunnelKind;
import io.jburrow.protocol.control.TunnelRequest;
import io.jburrow.protocol.testsupport.ScriptedPeer;
import io.jburrow.protocol.wire.Capability;
import io.jburrow.protocol.wire.ErrorCode;
import io.jburrow.protocol.wire.ProtocolVersion;
import io.jburrow.server.core.portpool.application.PortReservationService;
import io.jburrow.server.core.portpool.domain.PortRange;
import io.jburrow.server.core.routing.application.SubdomainRoutes;
import io.jburrow.server.core.tunnel.adapter.netty.edge.Cidr;
import io.jburrow.server.core.tunnel.adapter.netty.edge.EdgeServer;
import io.jburrow.server.core.tunnel.adapter.netty.edge.EdgeSettings;
import io.jburrow.server.core.tunnel.adapter.netty.edge.EdgeTransport;
import io.jburrow.server.core.tunnel.adapter.netty.edge.EngineDependencies;
import io.jburrow.server.core.tunnel.adapter.netty.edge.HttpSettingsFixture;
import io.jburrow.server.core.tunnel.adapter.netty.edge.TcpSettings;
import io.jburrow.server.core.tunnel.adapter.netty.tcp.NettyTunnelListeners;
import io.jburrow.server.core.tunnel.application.InMemoryAuthtokenVerifier;
import io.jburrow.server.core.tunnel.application.RecordingTunnelMetrics;
import io.jburrow.server.core.tunnel.application.RegisterTunnels;
import io.jburrow.server.core.tunnel.application.Registration;
import io.jburrow.server.core.tunnel.application.RegistrationFixtures;
import io.jburrow.server.core.tunnel.application.ResumeTokens;
import io.jburrow.server.core.tunnel.application.SessionAdmission;
import io.jburrow.server.core.tunnel.application.StreamQuota;
import io.jburrow.server.core.tunnel.domain.Principal;
import io.jburrow.server.core.tunnel.domain.SessionId;
import io.jburrow.server.core.tunnel.domain.SessionState;
import io.jburrow.server.core.tunnel.domain.Tunnel;
import io.jburrow.server.core.tunnel.domain.TunnelId;
import io.netty.util.concurrent.Future;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Step 2a against a real edge with real tunnel listeners, {@link ScriptedPeer}s playing the client, and {@code resume}
 * enabled, as the server will have it from the drain on. Each principal may have one session, so a session that
 * presents a token is admitted only because the session the token names has ended first.
 */
class ResumeIntegrationTest {

    private static final String ALICE_TOKEN = "jb_" + "a".repeat(43);
    private static final String BOB_TOKEN = "jb_" + "b".repeat(43);
    private static final String DEMO = "demo";
    private static final int FIRST_PORT = 41_700;
    private static final int POOL_SIZE = 5;
    private static final int REQUESTED_PORT = FIRST_PORT + 3;
    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final Duration REPLACE_TIMEOUT = SessionRegistry.REPLACE_TIMEOUT;
    private static final Duration STOP_TIMEOUT = Duration.ofSeconds(15);

    private final RecordingTunnelMetrics metrics = new RecordingTunnelMetrics();
    private final SessionAdmission admission = new SessionAdmission(16, 8, 1);
    private final PortReservationService<Principal> ports =
            RegistrationFixtures.ports(PortRange.parse(FIRST_PORT + "-" + (FIRST_PORT + POOL_SIZE - 1)), new Random(1));
    private final SubdomainRoutes<Principal, TunnelId> routes = RegistrationFixtures.routes();
    private final SessionRegistry registry = new SessionRegistry();
    private final ExecutorService blocking = Executors.newVirtualThreadPerTaskExecutor();
    private final List<ScriptedPeer> peers = new ArrayList<>();

    private EdgeServer edge;

    @AfterEach
    void stop() throws Exception {
        peers.forEach(ScriptedPeer::close);
        if (edge != null) {
            edge.stop(STOP_TIMEOUT).get(20, TimeUnit.SECONDS);
        }
        blocking.shutdownNow();
    }

    @ParameterizedTest
    @MethodSource("io.jburrow.server.core.tunnel.adapter.netty.support.Transports#available")
    void aTokenEndsItsLiveSessionAndTheNewOneGetsTheSameNameAndPort(EdgeTransport transport) throws Exception {
        start(transport);
        ScriptedPeer first = connect();
        UUID firstId = hello(first);
        Registered firstReply = register(first, ALICE_TOKEN, null);
        ResumeToken token = firstReply.resumeToken();
        assertThat(token).isNotNull().isEqualTo(first.lastResumeToken().orElseThrow());

        ScriptedPeer second = connect();
        UUID secondId = hello(second);
        Registered secondReply = register(second, ALICE_TOKEN, token);

        first.expectError(ErrorCode.SESSION_REPLACED, TIMEOUT);
        assertThat(secondReply.tunnels())
                .extracting(RegisteredTunnel::url)
                .containsExactlyElementsOf(
                        firstReply.tunnels().stream().map(RegisteredTunnel::url).toList());
        assertThat(secondReply.tunnels().get(1).publicPort()).isEqualTo(REQUESTED_PORT);
        assertThat(secondReply.resumeToken()).isNotNull().isNotEqualTo(token);
        assertThat(registry.find(new SessionId(firstId))).isEmpty();
        assertThat(registry.find(new SessionId(secondId))).isPresent();
        assertThat(admission.sessionsOf(new Principal("alice"))).isEqualTo(1);
        assertThat(metrics.events()).contains("resumed REPLACED", "sessionEnded SESSION_REPLACED");
        try (Socket visitor = new Socket(InetAddress.getLoopbackAddress(), REQUESTED_PORT)) {
            assertThat(visitor.isConnected()).isTrue();
        }
    }

    @ParameterizedTest
    @MethodSource("io.jburrow.server.core.tunnel.adapter.netty.support.Transports#available")
    void aTokenOfAnotherPrincipalLeavesItsSessionAlone(EdgeTransport transport) {
        start(transport);
        ScriptedPeer alice = connect();
        UUID aliceId = hello(alice);
        ResumeToken token = register(alice, ALICE_TOKEN, null).resumeToken();

        ScriptedPeer bob = connect();
        hello(bob);
        bob.send(new Frame.Register(new Register(BOB_TOKEN, List.of(http(DEMO)), token)));

        bob.expectError(ErrorCode.SUBDOMAIN_UNAVAILABLE, TIMEOUT);
        assertThat(state(aliceId)).isEqualTo(SessionState.ESTABLISHED);
        assertThat(metrics.events()).contains("resumed NONE").doesNotContain("resumed REPLACED");
    }

    @ParameterizedTest
    @MethodSource("io.jburrow.server.core.tunnel.adapter.netty.support.Transports#available")
    void aTokenWorksOnce(EdgeTransport transport) {
        start(transport);
        ScriptedPeer first = connect();
        hello(first);
        ResumeToken token = register(first, ALICE_TOKEN, null).resumeToken();
        ScriptedPeer second = connect();
        UUID secondId = hello(second);
        register(second, ALICE_TOKEN, token);
        first.expectError(ErrorCode.SESSION_REPLACED, TIMEOUT);

        ScriptedPeer third = connect();
        hello(third);
        third.send(registerFrame(ALICE_TOKEN, token));

        third.expectError(ErrorCode.SESSION_LIMIT_EXCEEDED, TIMEOUT);
        assertThat(state(secondId)).isEqualTo(SessionState.ESTABLISHED);
        assertThat(metrics.events().stream().filter("resumed REPLACED"::equals)).hasSize(1);
        assertThat(metrics.events()).contains("resumed NONE");
    }

    @ParameterizedTest
    @MethodSource("io.jburrow.server.core.tunnel.adapter.netty.support.Transports#available")
    void aTokenOfASessionThatEndedEndsNothingAndTheHoldsStillGrantItsNames(EdgeTransport transport) {
        start(transport);
        ScriptedPeer first = connect();
        UUID firstId = hello(first);
        Registered firstReply = register(first, ALICE_TOKEN, null);
        first.close();
        awaitTrue(() -> registry.find(new SessionId(firstId)).isEmpty()
                && ports.snapshot().reserved() == 0
                && admission.activeSessions() == 0);

        ScriptedPeer second = connect();
        hello(second);
        Registered secondReply = register(second, ALICE_TOKEN, firstReply.resumeToken());

        assertThat(secondReply.tunnels())
                .extracting(RegisteredTunnel::url)
                .containsExactlyElementsOf(
                        firstReply.tunnels().stream().map(RegisteredTunnel::url).toList());
        assertThat(metrics.events()).contains("resumed NONE").doesNotContain("resumed REPLACED");
    }

    @ParameterizedTest
    @MethodSource("io.jburrow.server.core.tunnel.adapter.netty.support.Transports#available")
    void aSessionThatNeverReleasesItsTunnelsHoldsTheWaitForTheTimeoutOnly(EdgeTransport transport) {
        StuckListeners stuck = new StuckListeners();
        start(transport, stuck);
        ScriptedPeer first = connect();
        hello(first);
        ResumeToken token = register(first, ALICE_TOKEN, null).resumeToken();
        stuck.stickNextUnbind();
        long started = System.nanoTime();

        ScriptedPeer second = connect();
        hello(second);
        second.send(registerFrame(ALICE_TOKEN, token));

        first.expectError(ErrorCode.SESSION_REPLACED, TIMEOUT);
        second.expectError(ErrorCode.PORT_UNAVAILABLE, TIMEOUT);
        assertThat(Duration.ofNanos(System.nanoTime() - started))
                .isBetween(REPLACE_TIMEOUT.minusMillis(200), REPLACE_TIMEOUT.plusSeconds(2));
        assertThat(metrics.events()).contains("resumed REPLACED");
    }

    private void start(EdgeTransport transport) {
        start(transport, null);
    }

    /** With {@code listeners} wrapping the real ones, or the real ones alone if null. */
    private void start(EdgeTransport transport, StuckListeners listeners) {
        EdgeSettings settings = new EdgeSettings(
                new InetSocketAddress(InetAddress.getLoopbackAddress(), 0),
                "localhost",
                transport,
                2,
                List.of(Cidr.parse("127.0.0.1/32"), Cidr.parse("::1/128")),
                16,
                8,
                Duration.ofSeconds(5),
                Duration.ofSeconds(5),
                Duration.ofSeconds(15),
                Duration.ofSeconds(45),
                128 * 1024,
                512 * 1024,
                ScriptedPeer.SERVER_WINDOWS,
                16,
                16,
                "test",
                HttpSettingsFixture.defaults(),
                Set.of(Capability.HTTP, Capability.RESUME));
        RegisterTunnels register = new RegisterTunnels(
                new InMemoryAuthtokenVerifier().with(ALICE_TOKEN, "alice").with(BOB_TOKEN, "bob"),
                admission,
                ports,
                routes,
                RegistrationFixtures.policy(2, "localhost"),
                metrics,
                registry,
                new ResumeTokens(),
                REPLACE_TIMEOUT);
        EngineDependencies deps = new EngineDependencies(
                register,
                admission,
                new StreamQuota(16),
                ports,
                routes,
                metrics,
                blocking,
                new TcpSettings("127.0.0.1", "localhost", 32 * 1024));
        TunnelListeners real = new NettyTunnelListeners(settings, deps);
        TunnelListeners tunnels = real;
        if (listeners != null) {
            listeners.delegate = real;
            tunnels = listeners;
        }
        edge = new EdgeServer(settings, deps, new ControlSessionInitializer(settings, deps, registry, tunnels));
        edge.start();
    }

    private ScriptedPeer connect() {
        ScriptedPeer peer = ScriptedPeer.connect(
                URI.create("ws://localhost:" + edge.boundPort() + ScriptedPeer.CONNECT_PATH), ProtocolVersion.CURRENT);
        peers.add(peer);
        return peer;
    }

    /** Says {@code HELLO} offering {@code http} and {@code resume}; returns the session's id. */
    private static UUID hello(ScriptedPeer peer) {
        peer.send(new Frame.Hello(new Hello(
                new ClientInfo("jburrow-test", "1.0", "linux", "amd64"),
                List.of("http", "resume"),
                ScriptedPeer.CLIENT_WINDOWS)));
        UUID id = peer.expect(Frame.ServerHello.class, TIMEOUT).message().sessionId();
        assertThat(peer.resumeNegotiated()).isTrue();
        return id;
    }

    /** Registers {@code demo} and {@link #REQUESTED_PORT}, presenting {@code resume} if not null. */
    private static Registered register(ScriptedPeer peer, String authtoken, ResumeToken resume) {
        peer.send(registerFrame(authtoken, resume));
        return peer.expect(Frame.Registered.class, TIMEOUT).message();
    }

    private static Frame registerFrame(String authtoken, ResumeToken resume) {
        return new Frame.Register(new Register(
                authtoken, List.of(http(DEMO), new TunnelRequest("ssh", TunnelKind.TCP, REQUESTED_PORT)), resume));
    }

    private static TunnelRequest http(String subdomain) {
        return new TunnelRequest("web", TunnelKind.HTTP, subdomain, null);
    }

    private SessionState state(UUID id) {
        SessionContext context = registry.find(new SessionId(id)).orElseThrow();
        try {
            return context.channel()
                    .eventLoop()
                    .submit(() -> context.session().state())
                    .get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private static void awaitTrue(BooleanSupplier condition) {
        long deadline = System.nanoTime() + TIMEOUT.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("Condition not met within " + TIMEOUT);
            }
            try {
                TimeUnit.MILLISECONDS.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted", e);
            }
        }
    }

    /** The real listeners, except that the next unbind never completes, as a listener that never closes would. */
    private static final class StuckListeners implements TunnelListeners {

        private volatile TunnelListeners delegate;
        private volatile boolean stickNext;

        void stickNextUnbind() {
            stickNext = true;
        }

        @Override
        public Future<List<Tunnel>> bind(SessionContext session, Registration registration) {
            return delegate.bind(session, registration);
        }

        @Override
        public Future<?> unbindAll(SessionContext session) {
            if (stickNext) {
                stickNext = false;
                return session.channel().eventLoop().newPromise();
            }
            return delegate.unbindAll(session);
        }

        @Override
        public void stopAccepting(SessionContext session) {
            delegate.stopAccepting(session);
        }
    }
}
