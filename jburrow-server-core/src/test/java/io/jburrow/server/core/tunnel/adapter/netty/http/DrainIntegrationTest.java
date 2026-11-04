package io.jburrow.server.core.tunnel.adapter.netty.http;

import static io.jburrow.server.core.tunnel.adapter.netty.http.HttpHarness.TIMEOUT;
import static io.jburrow.server.core.tunnel.adapter.netty.http.HttpHarness.TOKEN;
import static java.nio.charset.StandardCharsets.US_ASCII;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import io.jburrow.protocol.adapter.netty.Frame;
import io.jburrow.protocol.control.ClientInfo;
import io.jburrow.protocol.control.GoAway;
import io.jburrow.protocol.control.Hello;
import io.jburrow.protocol.control.Register;
import io.jburrow.protocol.control.RegisteredTunnel;
import io.jburrow.protocol.control.TunnelKind;
import io.jburrow.protocol.control.TunnelRequest;
import io.jburrow.protocol.testsupport.ScriptedPeer;
import io.jburrow.protocol.wire.ErrorCode;
import io.jburrow.server.core.tunnel.adapter.netty.edge.DrainSettings;
import io.jburrow.server.core.tunnel.adapter.netty.edge.EdgeTransport;
import io.jburrow.server.core.tunnel.adapter.netty.http.Visitor.Response;
import io.netty.buffer.Unpooled;
import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.Socket;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * {@code docs/phase-4-staying-connected/ARCHITECTURE.md} §The drain, against a real edge with real tunnel listeners and
 * {@link ScriptedPeer}s playing the client: {@code GOAWAY} only where {@code resume} was negotiated, no new stream once
 * a session drains, open streams finishing or cut at the timeout, and sessions in their handshake ended at once.
 */
class DrainIntegrationTest {

    private static final String TRANSPORTS = "io.jburrow.server.core.tunnel.adapter.netty.support.Transports#available";
    private static final Duration SPREAD = Duration.ofSeconds(2);
    private static final Duration LONG_DRAIN = Duration.ofSeconds(5);
    private static final Duration SHORT_DRAIN = Duration.ofSeconds(1);

    /** A client and the one tunnel it registered. */
    private record Client(ScriptedPeer peer, RegisteredTunnel tunnel) {}

    private HttpHarness harness;
    private Socket tcpVisitor;

    @AfterEach
    void stop() throws Exception {
        if (tcpVisitor != null) {
            tcpVisitor.close();
        }
        if (harness != null) {
            harness.close();
            assertThat(harness.streamQuota.inUse()).isZero();
        }
    }

    @ParameterizedTest
    @MethodSource(TRANSPORTS)
    void onlyASessionWithResumeIsSentGoAwayAndNothingOpensAfterIt(EdgeTransport transport) throws Exception {
        start(transport, LONG_DRAIN);
        ScriptedPeer resumed =
                established(List.of("http", "resume"), http("resumed")).peer();
        ScriptedPeer plain = established(List.of("http"), http("plain")).peer();

        CompletableFuture<Void> drained = harness.edge.drain();

        GoAway goAway = resumed.expect(Frame.GoAway.class, TIMEOUT).message();
        assertThat(goAway.reconnectAfterMs()).isBetween(0L, SPREAD.toMillis());
        assertThat(goAway.drainMs()).isEqualTo(LONG_DRAIN.toMillis());
        resumed.expectError(ErrorCode.SERVER_SHUTTING_DOWN, TIMEOUT);
        plain.expectError(ErrorCode.SERVER_SHUTTING_DOWN, TIMEOUT);
        drained.get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
    }

    @ParameterizedTest
    @MethodSource(TRANSPORTS)
    void anOpenStreamFinishesWhileNewVisitorsAreRefusedAndTheDrainEndsWithIt(EdgeTransport transport) throws Exception {
        start(transport, LONG_DRAIN);
        Client tcpClient = established(List.of("http", "resume"), tcpTunnel());
        ScriptedPeer tcp = tcpClient.peer();
        int port = tcpClient.tunnel().publicPort();
        int streamId = openTcpStream(tcp, port);
        Client webClient = established(List.of("http", "resume"), http("web"));
        ScriptedPeer web = webClient.peer();
        Visitor visitor = harness.visitor(webClient.tunnel().publicHost());
        harness.edgeConnection(visitor);
        long started = System.nanoTime();

        CompletableFuture<Void> drained = harness.edge.drain();
        tcp.expect(Frame.GoAway.class, TIMEOUT);
        web.expect(Frame.GoAway.class, TIMEOUT);

        assertThatExceptionOfType(ConnectException.class)
                .isThrownBy(() -> new Socket(InetAddress.getLoopbackAddress(), port).close());
        visitor.request("GET", "/");
        assertShuttingDown(visitor.response());

        tcp.sendData(streamId, Unpooled.copiedBuffer("still here", US_ASCII), TIMEOUT);
        assertThat(new String(tcpVisitor.getInputStream().readNBytes(10), US_ASCII))
                .isEqualTo("still here");
        tcpVisitor.shutdownOutput();
        Frame.Close visitorDone = tcp.expect(Frame.Close.class, TIMEOUT);
        assertThat(visitorDone.streamId()).isEqualTo(streamId);
        assertThat(visitorDone.reset()).isEmpty();
        tcp.send(Frame.Close.half(streamId));

        tcp.expectError(ErrorCode.SERVER_SHUTTING_DOWN, TIMEOUT);
        web.expectError(ErrorCode.SERVER_SHUTTING_DOWN, TIMEOUT);
        drained.get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(LONG_DRAIN.minusSeconds(2));
        assertThat(tcpVisitor.getInputStream().read()).isEqualTo(-1);
    }

    @ParameterizedTest
    @MethodSource(TRANSPORTS)
    void aStreamThatDoesntFinishIsCutAtTheTimeout(EdgeTransport transport) throws Exception {
        start(transport, SHORT_DRAIN);
        Client tcpClient = established(List.of("http", "resume"), tcpTunnel());
        ScriptedPeer tcp = tcpClient.peer();
        int streamId = openTcpStream(tcp, tcpClient.tunnel().publicPort());
        long started = System.nanoTime();

        CompletableFuture<Void> drained = harness.edge.drain();
        tcp.expect(Frame.GoAway.class, TIMEOUT);

        Frame.Close cut = tcp.expect(Frame.Close.class, TIMEOUT);
        assertThat(cut.streamId()).isEqualTo(streamId);
        assertThat(cut.reset()).contains(ErrorCode.CANCELLED);
        tcp.expectError(ErrorCode.SERVER_SHUTTING_DOWN, TIMEOUT);
        drained.get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        assertThat(Duration.ofNanos(System.nanoTime() - started))
                .isBetween(SHORT_DRAIN.minusMillis(100), SHORT_DRAIN.plusSeconds(2));
        assertVisitorCut(tcpVisitor.getInputStream());
    }

    @ParameterizedTest
    @MethodSource(TRANSPORTS)
    void aSessionInItsHandshakeEndsAtOnceWithoutGoAway(EdgeTransport transport) throws Exception {
        start(transport, LONG_DRAIN);
        ScriptedPeer handshaking = harness.connect();
        hello(handshaking, List.of("http", "resume"));
        long started = System.nanoTime();

        CompletableFuture<Void> drained = harness.edge.drain();

        handshaking.expectError(ErrorCode.SERVER_SHUTTING_DOWN, TIMEOUT);
        drained.get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(LONG_DRAIN.minusSeconds(2));
    }

    @ParameterizedTest
    @MethodSource(TRANSPORTS)
    void aRequestForADrainingSessionsTunnelIsToldToRetryLater(EdgeTransport transport) throws Exception {
        start(transport, LONG_DRAIN);
        Client webClient = established(List.of("http", "resume"), http("web"));
        ScriptedPeer web = webClient.peer();
        Visitor visitor = harness.visitor(webClient.tunnel().publicHost());

        harness.sessions.drain(LONG_DRAIN);
        web.expect(Frame.GoAway.class, TIMEOUT);
        visitor.request("GET", "/");

        assertShuttingDown(visitor.response());
        assertThat(harness.metrics.events()).contains("visitorRefused DRAINING");
    }

    private void start(EdgeTransport transport, Duration drain) {
        harness = new HttpHarness(HttpHarness.Settings.defaults(transport).resume(new DrainSettings(drain, SPREAD)));
    }

    /** A session that offered {@code capabilities} and registered {@code tunnel}. */
    private Client established(List<String> capabilities, TunnelRequest tunnel) {
        ScriptedPeer peer = harness.connect();
        peer.ignoreWindowUpdates();
        hello(peer, capabilities);
        peer.send(new Frame.Register(new Register(TOKEN, List.of(tunnel))));
        RegisteredTunnel registered =
                peer.expect(Frame.Registered.class, TIMEOUT).message().tunnels().getFirst();
        assertThat(registered.kind()).isEqualTo(tunnel.kind());
        assertThat(peer.resumeNegotiated()).isEqualTo(capabilities.contains("resume"));
        return new Client(peer, registered);
    }

    private static void hello(ScriptedPeer peer, List<String> capabilities) {
        peer.send(new Frame.Hello(new Hello(
                new ClientInfo("jburrow-test", "1.0", "linux", "amd64"), capabilities, ScriptedPeer.CLIENT_WINDOWS)));
        peer.expect(Frame.ServerHello.class, TIMEOUT);
    }

    /** Connects a visitor to the TCP tunnel on {@code port}; returns the stream the client was told about. */
    private int openTcpStream(ScriptedPeer peer, int port) throws IOException {
        tcpVisitor = new Socket(InetAddress.getLoopbackAddress(), port);
        tcpVisitor.setSoTimeout((int) TIMEOUT.toMillis());
        return peer.expect(Frame.Open.class, TIMEOUT).streamId();
    }

    private static TunnelRequest http(String subdomain) {
        return new TunnelRequest("web", TunnelKind.HTTP, subdomain, null);
    }

    private static TunnelRequest tcpTunnel() {
        return new TunnelRequest("ssh", TunnelKind.TCP, null, null);
    }

    private static void assertShuttingDown(Response response) {
        assertThat(response.status()).isEqualTo(503);
        assertThat(response.header("retry-after")).isEqualTo("10");
        assertThat(response.text()).contains("SERVER_SHUTTING_DOWN");
    }

    /** A cut visitor reads end of stream or a reset, whichever its kernel reports first. */
    private static void assertVisitorCut(InputStream visitor) {
        try {
            assertThat(visitor.read()).isEqualTo(-1);
        } catch (IOException reset) {
            assertThat(reset).hasMessageContaining("reset");
        }
    }
}
