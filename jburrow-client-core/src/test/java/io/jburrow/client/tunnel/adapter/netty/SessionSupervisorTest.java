package io.jburrow.client.tunnel.adapter.netty;

import static org.assertj.core.api.Assertions.assertThat;

import io.jburrow.client.ClientSettings;
import io.jburrow.client.ReconnectPolicy;
import io.jburrow.client.TunnelHandle;
import io.jburrow.client.TunnelStartException;
import io.jburrow.client.tunnel.domain.LocalTarget;
import io.jburrow.client.tunnel.domain.TunnelClosed;
import io.jburrow.client.tunnel.domain.TunnelSpec;
import io.jburrow.protocol.adapter.netty.Frame;
import io.jburrow.protocol.control.ClientInfo;
import io.jburrow.protocol.control.ErrorBody;
import io.jburrow.protocol.control.GoAway;
import io.jburrow.protocol.control.Open;
import io.jburrow.protocol.control.Registered;
import io.jburrow.protocol.control.RegisteredTunnel;
import io.jburrow.protocol.control.RemoteAddress;
import io.jburrow.protocol.control.ResumeToken;
import io.jburrow.protocol.control.ServerHello;
import io.jburrow.protocol.control.ServerInfo;
import io.jburrow.protocol.control.SessionLimits;
import io.jburrow.protocol.control.SubdomainName;
import io.jburrow.protocol.control.TunnelKind;
import io.jburrow.protocol.control.TunnelRequest;
import io.jburrow.protocol.testsupport.RefusingServer;
import io.jburrow.protocol.testsupport.ScriptedPeer;
import io.jburrow.protocol.wire.ErrorCode;
import io.jburrow.protocol.wire.FrameLimits;
import io.jburrow.protocol.wire.ProtocolVersion;
import java.io.IOException;
import java.net.BindException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The real client running one session after another against {@link ScriptedPeer}s playing the server: ARCHITECTURE.md
 * §The client, phase 4. A test hands the port from one peer to the next before the first lets the client go, so each
 * reconnect lands on a peer that is already listening.
 */
class SessionSupervisorTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final Duration REBIND_PAUSE = Duration.ofMillis(20);
    private static final String AUTHTOKEN = "jb_" + "t".repeat(43);
    private static final ClientInfo CLIENT = new ClientInfo("jburrow-test", "1.0.0", "linux", "amd64");
    private static final SessionLimits LIMITS = new SessionLimits(FrameLimits.MAX_DATA_PAYLOAD, 1, 100, 15_000, 45_000);
    private static final UUID FIRST = UUID.fromString("0b8e5c1e-7c55-4c46-9a3e-2f1d0c9e4b7a");
    private static final UUID SECOND = UUID.fromString("1c9f6d2f-8d66-4d57-ab4f-3a2e1dafb58b");
    private static final UUID THIRD = UUID.fromString("2da07e30-9e77-4e68-bc50-4b3f2eb0c69c");
    private static final UUID TUNNEL_ID = UUID.fromString("5d0f4f0e-31a6-4a39-8d51-0c0f7f1f6c23");
    private static final ResumeToken FIRST_TOKEN = new ResumeToken("jbr_AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8");
    private static final ResumeToken SECOND_TOKEN = new ResumeToken("jbr_ICEiIyQlJicoKSorLC0uLzAxMjM0NTY3ODk6Ozw9Pj8");
    private static final LocalTarget WEB = LocalTarget.of("localhost", 3000);
    private static final RegisteredTunnel OTTER =
            RegisteredTunnel.http("default", TUNNEL_ID, "https", "quiet-otter-4f2a.example.dev", 443);
    private static final RegisteredTunnel HERON =
            RegisteredTunnel.http("default", TUNNEL_ID, "https", "brave-heron-91c0.example.dev", 443);
    private static final List<String> HTTP_AND_RESUME = List.of("http", "resume");
    /** The standard schedule with each second lasting 20 ms. */
    private static final ReconnectPolicy FAST = ReconnectPolicy.standard().scaled(Duration.ofMillis(20));

    private final ExecutorService threads = Executors.newVirtualThreadPerTaskExecutor();
    private final RecordingEvents events = new RecordingEvents();
    private final List<ScriptedPeer> peers = new ArrayList<>();
    private NettyTunnelClient client;
    private ServerSocket local;

    @AfterEach
    void stop() throws IOException {
        if (client != null) {
            client.close();
        }
        peers.forEach(ScriptedPeer::close);
        if (local != null) {
            local.close();
        }
        threads.shutdownNow();
    }

    @Test
    void aDroppedConnectionComesBackWithTheSameNameAndTheToken() throws Exception {
        ScriptedPeer first = listen(0);
        TunnelHandle handle = establish(first, TunnelSpec.http("default", WEB), FAST, OTTER);
        ScriptedPeer second = handOver(first);

        first.dropConnection();
        Frame.Register register = serve(second, SECOND, HTTP_AND_RESUME);

        assertThat(register.message().tunnels())
                .containsExactly(new TunnelRequest("default", TunnelKind.HTTP, "quiet-otter-4f2a", null));
        assertThat(register.message().resume()).isEqualTo(FIRST_TOKEN);
        second.send(new Frame.Registered(new Registered(List.of(OTTER), SECOND_TOKEN)));
        events.await("reconnected", TIMEOUT);
        assertThat(events.events())
                .containsExactly(
                        "established " + FIRST + " https://quiet-otter-4f2a.example.dev",
                        "disconnected CONNECTION_LOST -",
                        "reconnected " + SECOND + " https://quiet-otter-4f2a.example.dev sameUrls=true");
        assertThat(events.retries().getFirst()).isLessThanOrEqualTo(Duration.ofMillis(10));
        assertThat(handle.sessionId()).isEqualTo(SECOND.toString());
        assertThat(handle.tunnels()).containsExactly(OTTER);
        assertThat(handle.closed().toCompletableFuture()).isNotDone();
    }

    @Test
    void afterGoAwayAndTheServersErrorTheClientWaitsTheServersDelay() throws Exception {
        ScriptedPeer first = listen(0);
        establish(first, TunnelSpec.http("default", WEB), FAST, OTTER);
        ScriptedPeer second = handOver(first);

        first.send(new Frame.GoAway(new GoAway(400, 5_000)));
        long ended = System.nanoTime();
        first.send(shuttingDown());
        first.close();
        second.expect(Frame.Hello.class, TIMEOUT);

        assertThat(Duration.ofNanos(System.nanoTime() - ended)).isGreaterThanOrEqualTo(Duration.ofMillis(400));
        second.send(serverHello(SECOND, HTTP_AND_RESUME));
        assertThat(second.expect(Frame.Register.class, TIMEOUT).message().resume())
                .isEqualTo(FIRST_TOKEN);
        second.send(new Frame.Registered(new Registered(List.of(OTTER), SECOND_TOKEN)));
        events.await("reconnected", TIMEOUT);
        assertThat(events.events())
                .containsExactly(
                        "established " + FIRST + " https://quiet-otter-4f2a.example.dev",
                        "disconnected SERVER_ERROR SERVER_SHUTTING_DOWN",
                        "reconnected " + SECOND + " https://quiet-otter-4f2a.example.dev sameUrls=true");
        assertThat(events.retries()).containsExactly(Duration.ofMillis(400));
    }

    @Test
    void anAttemptRefusedWithoutACodeReportsItsHttpStatus() throws Exception {
        ScriptedPeer first = listen(0);
        establish(first, TunnelSpec.http("default", WEB), FAST, OTTER);
        int port = first.port();
        first.stopListening();

        try (RefusingServer refusing =
                RefusingServer.start(port, 503, "text/html", "<html>503 Service Temporarily Unavailable</html>")) {
            first.dropConnection();
            long deadline = System.nanoTime() + TIMEOUT.toNanos();
            while (events.disconnects().size() < 2 && System.nanoTime() < deadline) {
                Thread.sleep(5);
            }

            List<TunnelClosed> disconnects = events.disconnects();
            assertThat(disconnects).hasSizeGreaterThanOrEqualTo(2);
            assertThat(disconnects.getFirst().upgradeStatus()).isEmpty();
            TunnelClosed refused = disconnects.get(1);
            assertThat(refused.reason()).isEqualTo(TunnelClosed.Reason.CONNECTION_LOST);
            assertThat(refused.code()).isEmpty();
            assertThat(refused.upgradeStatus()).isEqualTo(OptionalInt.of(503));
        }
    }

    @Test
    void anInvalidAuthtokenOnAReconnectStopsTheClient() throws Exception {
        ScriptedPeer first = listen(0);
        TunnelHandle handle = establish(first, TunnelSpec.http("default", WEB), FAST, OTTER);
        ScriptedPeer second = handOver(first);

        first.dropConnection();
        serve(second, SECOND, HTTP_AND_RESUME);
        second.send(new Frame.Error(new ErrorBody(ErrorCode.AUTHTOKEN_INVALID, "The authtoken is unknown.", null)));
        second.close();

        TunnelClosed closed = handle.closed().toCompletableFuture().get(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        assertThat(closed.reason()).isEqualTo(TunnelClosed.Reason.SERVER_ERROR);
        assertThat(closed.code()).contains(ErrorCode.AUTHTOKEN_INVALID);
        assertThat(events.events())
                .containsExactly(
                        "established " + FIRST + " https://quiet-otter-4f2a.example.dev",
                        "disconnected CONNECTION_LOST -",
                        "closed SERVER_ERROR AUTHTOKEN_INVALID");
    }

    @Test
    void aNameTheServerChoseThatWasTakenIsAskedForOnceMoreWithoutIt() throws Exception {
        ScriptedPeer first = listen(0);
        TunnelHandle handle = establish(first, TunnelSpec.http("default", WEB), FAST, OTTER);
        ScriptedPeer second = handOver(first);

        first.dropConnection();
        assertThat(serve(second, SECOND, HTTP_AND_RESUME)
                        .message()
                        .tunnels()
                        .getFirst()
                        .subdomain())
                .isEqualTo("quiet-otter-4f2a");
        ScriptedPeer third = handOver(second);
        second.send(
                new Frame.Error(new ErrorBody(ErrorCode.SUBDOMAIN_UNAVAILABLE, "The subdomain is taken.", "default")));
        second.close();
        Frame.Register again = serve(third, THIRD, HTTP_AND_RESUME);

        assertThat(again.message().tunnels())
                .containsExactly(new TunnelRequest("default", TunnelKind.HTTP, null, null));
        assertThat(again.message().resume()).isEqualTo(FIRST_TOKEN);
        third.send(new Frame.Registered(new Registered(List.of(HERON), SECOND_TOKEN)));
        events.await("reconnected", TIMEOUT);
        assertThat(events.events())
                .containsExactly(
                        "established " + FIRST + " https://quiet-otter-4f2a.example.dev",
                        "disconnected CONNECTION_LOST -",
                        "disconnected SERVER_ERROR SUBDOMAIN_UNAVAILABLE",
                        "reconnected " + THIRD + " https://brave-heron-91c0.example.dev sameUrls=false");
        assertThat(events.retries().get(1)).isZero();
        assertThat(handle.tunnels()).containsExactly(HERON);
    }

    @Test
    void aNameTheUserChoseThatWasTakenStopsTheClient() throws Exception {
        ScriptedPeer first = listen(0);
        TunnelSpec chosen = TunnelSpec.http("default", WEB).subdomain(new SubdomainName("quiet-otter-4f2a"));
        TunnelHandle handle = establish(first, chosen, FAST, OTTER);
        ScriptedPeer second = handOver(first);

        first.dropConnection();
        serve(second, SECOND, HTTP_AND_RESUME);
        second.send(
                new Frame.Error(new ErrorBody(ErrorCode.SUBDOMAIN_UNAVAILABLE, "The subdomain is taken.", "default")));
        second.close();

        TunnelClosed closed = handle.closed().toCompletableFuture().get(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        assertThat(closed.code()).contains(ErrorCode.SUBDOMAIN_UNAVAILABLE);
        assertThat(events.events()).last().isEqualTo("closed SERVER_ERROR SUBDOMAIN_UNAVAILABLE");
    }

    @Test
    void everyStreamIsReportedClosedBeforeTheDisconnectAndTheTcpPortIsAskedForAgain() throws Exception {
        local = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
        ScriptedPeer first = listen(0);
        RegisteredTunnel port = RegisteredTunnel.of("default", TUNNEL_ID, TunnelKind.TCP, "tcp.example.dev", 20_417);
        establish(first, TunnelSpec.tcp("default", LocalTarget.of("127.0.0.1", local.getLocalPort())), FAST, port);
        first.send(new Frame.Open(1, new Open(TUNNEL_ID, TunnelKind.TCP, new RemoteAddress("203.0.113.9", 51_234))));
        try (Socket accepted = local.accept()) {
            events.await("opened 1", TIMEOUT);
            ScriptedPeer second = handOver(first);

            first.dropConnection();
            Frame.Register register = serve(second, SECOND, List.of("resume"));

            assertThat(register.message().tunnels())
                    .containsExactly(new TunnelRequest("default", TunnelKind.TCP, null, 20_417));
            second.send(new Frame.Registered(new Registered(List.of(port), SECOND_TOKEN)));
            events.await("reconnected", TIMEOUT);
        }
        assertThat(events.events())
                .containsExactly(
                        "established " + FIRST + " tcp://tcp.example.dev:20417",
                        "opened 1 default 203.0.113.9:51234",
                        "stream-closed 1 in=0 out=0 reset=CANCELLED",
                        "disconnected CONNECTION_LOST -",
                        "reconnected " + SECOND + " tcp://tcp.example.dev:20417 sameUrls=true");
    }

    @Test
    void closingDuringTheWaitStopsTheClientAtOnce() throws Exception {
        ScriptedPeer first = listen(0);
        TunnelHandle handle = establish(first, TunnelSpec.http("default", WEB), FAST, OTTER);
        first.send(new Frame.GoAway(new GoAway(60_000, 5_000)));
        first.send(shuttingDown());
        first.close();
        events.await("disconnected", TIMEOUT);

        long closing = System.nanoTime();
        handle.close();
        TunnelClosed closed = handle.closed().toCompletableFuture().get(TIMEOUT.toSeconds(), TimeUnit.SECONDS);

        assertThat(Duration.ofNanos(System.nanoTime() - closing)).isLessThan(Duration.ofSeconds(5));
        assertThat(closed.reason()).isEqualTo(TunnelClosed.Reason.USER_CLOSED);
        assertThat(events.events())
                .containsExactly(
                        "established " + FIRST + " https://quiet-otter-4f2a.example.dev",
                        "disconnected SERVER_ERROR SERVER_SHUTTING_DOWN",
                        "closed USER_CLOSED -");
    }

    @Test
    void withNeverTheFirstSessionsEndStopsTheClientAsBefore() throws Exception {
        ScriptedPeer first = listen(0);
        TunnelHandle handle = establish(first, TunnelSpec.http("default", WEB), ReconnectPolicy.never(), OTTER);

        first.dropConnection();

        TunnelClosed closed = handle.closed().toCompletableFuture().get(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        assertThat(closed.reason()).isEqualTo(TunnelClosed.Reason.CONNECTION_LOST);
        assertThat(events.events())
                .containsExactly(
                        "established " + FIRST + " https://quiet-otter-4f2a.example.dev", "closed CONNECTION_LOST -");
    }

    @Test
    void aFirstSessionToAStoppingServerIsTriedAgainSilently() throws Exception {
        ScriptedPeer first = listen(0);
        client = client(first.uri(), TunnelSpec.tcp("default", WEB), FAST);
        Future<TunnelHandle> start = threads.submit(client::start);
        first.expect(Frame.Hello.class, TIMEOUT);
        ScriptedPeer second = handOver(first);

        first.send(shuttingDown());
        first.close();
        serve(second, SECOND, List.of("resume"));
        RegisteredTunnel port = RegisteredTunnel.of("default", TUNNEL_ID, TunnelKind.TCP, "tcp.example.dev", 20_417);
        second.send(new Frame.Registered(new Registered(List.of(port), SECOND_TOKEN)));

        TunnelHandle handle = start.get(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        assertThat(handle.sessionId()).isEqualTo(SECOND.toString());
        assertThat(events.events()).containsExactly("established " + SECOND + " tcp://tcp.example.dev:20417");
    }

    @Test
    void aFirstSessionRefusedForGoodFailsAtOnce() throws Exception {
        ScriptedPeer first = listen(0);
        client = client(first.uri(), TunnelSpec.tcp("default", WEB), FAST);
        Future<TunnelHandle> start = threads.submit(client::start);
        first.expect(Frame.Hello.class, TIMEOUT);
        first.send(serverHello(FIRST, List.of("resume")));
        first.expect(Frame.Register.class, TIMEOUT);

        first.send(new Frame.Error(new ErrorBody(ErrorCode.AUTHTOKEN_INVALID, "The authtoken is unknown.", null)));

        TunnelStartException failure = failure(start);
        assertThat(failure.code()).contains(ErrorCode.AUTHTOKEN_INVALID);
        assertThat(events.events()).isEmpty();
    }

    // Helpers.

    private TunnelHandle establish(
            ScriptedPeer peer, TunnelSpec spec, ReconnectPolicy policy, RegisteredTunnel registered) throws Exception {
        client = client(peer.uri(), spec, policy);
        Future<TunnelHandle> start = threads.submit(client::start);
        Frame.Register register = serve(peer, FIRST, HTTP_AND_RESUME);
        assertThat(register.message().resume()).isNull();
        peer.send(new Frame.Registered(new Registered(List.of(registered), FIRST_TOKEN)));
        return start.get(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
    }

    /** Answers the client's {@code HELLO} as session {@code sessionId}, and returns its {@code REGISTER}. */
    private static Frame.Register serve(ScriptedPeer peer, UUID sessionId, List<String> capabilities) {
        peer.expect(Frame.Hello.class, TIMEOUT);
        peer.send(serverHello(sessionId, capabilities));
        return peer.expect(Frame.Register.class, TIMEOUT);
    }

    /**
     * A new peer on {@code old}'s port, which then accepts nothing more; its connection stays open. The port is free
     * between the two listeners, and another socket on the machine can hold it for a moment, so the bind is retried.
     */
    private ScriptedPeer handOver(ScriptedPeer old) {
        int port = old.port();
        old.stopListening();
        long deadline = System.nanoTime() + TIMEOUT.toNanos();
        while (true) {
            try {
                return listen(port);
            } catch (Exception e) {
                if (!(e instanceof BindException) || System.nanoTime() - deadline > 0) {
                    throw e;
                }
                LockSupport.parkNanos(REBIND_PAUSE.toNanos());
            }
        }
    }

    private ScriptedPeer listen(int port) {
        ScriptedPeer peer = ScriptedPeer.listen(port, ProtocolVersion.CURRENT);
        peers.add(peer);
        return peer;
    }

    private NettyTunnelClient client(URI server, TunnelSpec spec, ReconnectPolicy policy) {
        return new NettyTunnelClient(
                new ClientSettings(
                        server,
                        AUTHTOKEN,
                        List.of(spec),
                        events,
                        CLIENT,
                        Optional.empty(),
                        TIMEOUT,
                        ClientSettings.DEFAULT_WINDOWS,
                        ClientSettings.IMPLEMENTED_CAPABILITIES,
                        policy),
                HostResolver.SYSTEM);
    }

    private static Frame.ServerHello serverHello(UUID sessionId, List<String> capabilities) {
        return new Frame.ServerHello(new ServerHello(
                sessionId,
                new ServerInfo("jburrow-server", "0.4.0"),
                capabilities,
                LIMITS,
                ScriptedPeer.SERVER_WINDOWS));
    }

    private static Frame.Error shuttingDown() {
        return new Frame.Error(new ErrorBody(ErrorCode.SERVER_SHUTTING_DOWN, "The server is restarting.", null));
    }

    private static TunnelStartException failure(Future<TunnelHandle> start) throws Exception {
        try {
            start.get(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof TunnelStartException failure) {
                return failure;
            }
            throw e;
        }
        throw new AssertionError("start() succeeded");
    }
}
