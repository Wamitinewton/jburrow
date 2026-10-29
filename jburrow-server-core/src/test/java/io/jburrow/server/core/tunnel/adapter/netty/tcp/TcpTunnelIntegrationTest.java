package io.jburrow.server.core.tunnel.adapter.netty.tcp;

import static io.jburrow.server.core.tunnel.adapter.netty.tcp.TunnelHarness.TIMEOUT;
import static io.jburrow.server.core.tunnel.adapter.netty.tcp.TunnelHarness.awaitTrue;
import static io.jburrow.server.core.tunnel.adapter.netty.tcp.TunnelHarness.canBind;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.jburrow.protocol.adapter.netty.Frame;
import io.jburrow.protocol.control.RegisteredTunnel;
import io.jburrow.protocol.control.RemoteAddress;
import io.jburrow.protocol.control.TunnelKind;
import io.jburrow.protocol.testsupport.ScriptedPeer;
import io.jburrow.protocol.wire.ErrorCode;
import io.jburrow.protocol.wire.FrameLimits;
import io.jburrow.server.core.portpool.domain.PortRange;
import io.jburrow.server.core.tunnel.adapter.netty.control.SessionContext;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.BindException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.random.RandomGenerator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The server half of a TCP tunnel over real sockets: real visitor connections on one side, {@link ScriptedPeer}
 * playing the client by hand on the other, returning credit as a correct client does.
 */
class TcpTunnelIntegrationTest {

    private static final int FIRST_PORT = 41_100;
    private static final int POOL_SIZE = 10;
    private static final int TRANSFER = 8 * 1024 * 1024;

    private final ExecutorService threads = Executors.newVirtualThreadPerTaskExecutor();
    private TunnelHarness harness;

    @AfterEach
    void stop() throws Exception {
        if (harness != null) {
            harness.close();
            assertThat(harness.streamQuota.inUse()).isZero();
        }
        threads.shutdownNow();
    }

    @Test
    void aVisitorBecomesAStreamOnTheSessionsEventLoopAndBytesFlowBothWays() throws Exception {
        start();
        ScriptedPeer peer = harness.connect();
        RegisteredTunnel tunnel = establish(peer);

        try (Socket visitor = visitor(tunnel)) {
            Frame.Open open = peer.expect(Frame.Open.class, TIMEOUT);
            assertThat(open.streamId()).isEqualTo(1);
            assertThat(open.message().tunnelId()).isEqualTo(tunnel.tunnelId());
            assertThat(open.message().kind()).isEqualTo(TunnelKind.TCP);
            assertThat(open.message().remote()).isEqualTo(new RemoteAddress("127.0.0.1", visitor.getLocalPort()));

            write(visitor, "hello");
            assertThat(new String(readData(peer, 5), StandardCharsets.US_ASCII)).isEqualTo("hello");
            peer.send(data("world"));
            assertThat(readFully(visitor.getInputStream(), 5)).isEqualTo("world".getBytes(StandardCharsets.US_ASCII));

            SessionContext session = harness.onlySession();
            List<Channel> visitors = TunnelHarness.onLoop(
                    session, () -> ((StreamRelay) session.streams().orElseThrow()).visitorChannels());
            assertThat(visitors)
                    .singleElement()
                    .satisfies(channel -> assertThat(channel.eventLoop())
                            .isSameAs(session.channel().eventLoop()));
        }
    }

    @Test
    void eightMebibytesEachWayArriveIntact() throws Exception {
        start();
        ScriptedPeer peer = harness.connect();
        RegisteredTunnel tunnel = establish(peer);
        peer.ignoreWindowUpdates();
        byte[] up = randomBytes(1);
        byte[] down = randomBytes(2);

        try (Socket visitor = visitor(tunnel)) {
            peer.expect(Frame.Open.class, TIMEOUT);
            Future<?> upload = threads.submit(() -> {
                visitor.getOutputStream().write(up);
                return null;
            });
            Future<?> download = threads.submit(() -> {
                for (int offset = 0; offset < down.length; offset += FrameLimits.MAX_DATA_PAYLOAD) {
                    int length = Math.min(FrameLimits.MAX_DATA_PAYLOAD, down.length - offset);
                    peer.sendData(1, Unpooled.wrappedBuffer(down, offset, length), TIMEOUT);
                }
                return null;
            });
            Future<byte[]> received = threads.submit(() -> readFully(visitor.getInputStream(), TRANSFER));

            byte[] uploaded = readData(peer, TRANSFER);

            upload.get(30, TimeUnit.SECONDS);
            download.get(30, TimeUnit.SECONDS);
            assertThat(sha256(uploaded)).isEqualTo(sha256(up));
            assertThat(sha256(received.get(30, TimeUnit.SECONDS))).isEqualTo(sha256(down));
        }
    }

    @Test
    void theVisitorClosingItsHalfFirstStillReceivesUntilTheClientCloses() throws Exception {
        start();
        ScriptedPeer peer = harness.connect();
        RegisteredTunnel tunnel = establish(peer);

        try (Socket visitor = visitor(tunnel)) {
            peer.expect(Frame.Open.class, TIMEOUT);
            write(visitor, "request");
            visitor.shutdownOutput();
            assertThat(readData(peer, 7)).isEqualTo("request".getBytes(StandardCharsets.US_ASCII));
            assertThat(peer.expect(Frame.Close.class, TIMEOUT)).isEqualTo(Frame.Close.half(1));

            peer.send(data("response"));
            assertThat(readFully(visitor.getInputStream(), 8))
                    .isEqualTo("response".getBytes(StandardCharsets.US_ASCII));
            peer.send(Frame.Close.half(1));

            assertThat(visitor.getInputStream().read()).isEqualTo(-1);
            awaitTrue(() -> harness.metrics.events().contains("streamClosed TCP"));
            assertThat(openVisitors()).isEmpty();
        }
    }

    @Test
    void theClientClosingItsHalfFirstStillReceivesUntilTheVisitorCloses() throws Exception {
        start();
        ScriptedPeer peer = harness.connect();
        RegisteredTunnel tunnel = establish(peer);

        try (Socket visitor = visitor(tunnel)) {
            peer.expect(Frame.Open.class, TIMEOUT);
            peer.send(Frame.Close.half(1));
            assertThat(visitor.getInputStream().read()).isEqualTo(-1);

            write(visitor, "still sending");
            assertThat(readData(peer, 13)).isEqualTo("still sending".getBytes(StandardCharsets.US_ASCII));
            visitor.shutdownOutput();

            assertThat(peer.expect(Frame.Close.class, TIMEOUT)).isEqualTo(Frame.Close.half(1));
            awaitTrue(() -> harness.metrics.events().contains("streamClosed TCP"));
        }
    }

    @Test
    void aVisitorResetBecomesClosePeerReset() throws Exception {
        start();
        ScriptedPeer peer = harness.connect();
        RegisteredTunnel tunnel = establish(peer);
        Socket visitor = visitor(tunnel);
        peer.expect(Frame.Open.class, TIMEOUT);

        visitor.setSoLinger(true, 0);
        visitor.close();

        assertThat(peer.expect(Frame.Close.class, TIMEOUT)).isEqualTo(Frame.Close.reset(1, ErrorCode.PEER_RESET));
    }

    @Test
    void aClientResetResetsTheVisitorConnection() throws Exception {
        start();
        ScriptedPeer peer = harness.connect();
        RegisteredTunnel tunnel = establish(peer);

        try (Socket visitor = visitor(tunnel)) {
            peer.expect(Frame.Open.class, TIMEOUT);
            peer.send(Frame.Close.reset(1, ErrorCode.LOCAL_SERVICE_UNREACHABLE));

            assertThatThrownBy(() -> visitor.getInputStream().read())
                    .isInstanceOf(SocketException.class)
                    .hasMessageContaining("reset");
            awaitTrue(() -> harness.metrics.events().contains("streamReset LOCAL_SERVICE_UNREACHABLE REMOTE"));
        }
    }

    @Test
    void dataFromTheClientAfterItsOwnCloseIsResetWithStreamClosed() throws Exception {
        start();
        ScriptedPeer peer = harness.connect();
        RegisteredTunnel tunnel = establish(peer);

        try (Socket visitor = visitor(tunnel)) {
            peer.expect(Frame.Open.class, TIMEOUT);
            peer.send(Frame.Close.half(1));
            peer.send(data("too late"));

            assertThat(peer.expect(Frame.Close.class, TIMEOUT))
                    .isEqualTo(Frame.Close.reset(1, ErrorCode.STREAM_CLOSED));
        }
    }

    @Test
    void dataOnAStreamNeverOpenedEndsTheSessionWithProtocolError() throws Exception {
        start();
        ScriptedPeer peer = harness.connect();
        RegisteredTunnel tunnel = establish(peer);

        try (Socket visitor = visitor(tunnel)) {
            peer.expect(Frame.Open.class, TIMEOUT);
            peer.send(new Frame.Data(2, Unpooled.copiedBuffer("stray", StandardCharsets.US_ASCII)));

            assertThat(peer.expect(Frame.Close.class, TIMEOUT)).isEqualTo(Frame.Close.reset(1, ErrorCode.CANCELLED));
            peer.expectError(ErrorCode.PROTOCOL_ERROR, TIMEOUT);
        }
    }

    @Test
    void aSecondVisitorGetsTheNextStreamAndBothRelayAtOnce() throws Exception {
        start();
        ScriptedPeer peer = harness.connect();
        RegisteredTunnel tunnel = establish(peer);

        try (Socket first = visitor(tunnel);
                Socket second = visitor(tunnel)) {
            assertThat(peer.expect(Frame.Open.class, TIMEOUT).streamId()).isEqualTo(1);
            assertThat(peer.expect(Frame.Open.class, TIMEOUT).streamId()).isEqualTo(2);

            write(second, "two");
            assertThat(readData(peer, 2, 3)).isEqualTo("two".getBytes(StandardCharsets.US_ASCII));
            write(first, "one");
            assertThat(readData(peer, 1, 3)).isEqualTo("one".getBytes(StandardCharsets.US_ASCII));
            peer.send(new Frame.Data(1, Unpooled.copiedBuffer("eno", StandardCharsets.US_ASCII)));
            peer.send(new Frame.Data(2, Unpooled.copiedBuffer("owt", StandardCharsets.US_ASCII)));

            assertThat(readFully(second.getInputStream(), 3)).isEqualTo("owt".getBytes(StandardCharsets.US_ASCII));
            assertThat(readFully(first.getInputStream(), 3)).isEqualTo("eno".getBytes(StandardCharsets.US_ASCII));
            assertThat(openVisitors()).hasSize(2);
        }
    }

    @Test
    void theSessionClosingResetsItsVisitorAndFreesItsPort() throws Exception {
        start();
        ScriptedPeer peer = harness.connect();
        RegisteredTunnel tunnel = establish(peer);

        try (Socket visitor = visitor(tunnel)) {
            peer.expect(Frame.Open.class, TIMEOUT);
            peer.close();

            assertThatThrownBy(() -> visitor.getInputStream().read())
                    .isInstanceOf(SocketException.class)
                    .hasMessageContaining("reset");
        }
        awaitTrue(() -> harness.ports.snapshot().reserved() == 0);
        assertThat(canBind(tunnel.publicPort())).isTrue();
        assertThat(harness.metrics.events()).contains("streamReset CANCELLED LOCAL");
    }

    @Test
    void shuttingDownResetsOpenStreamsWithCancelledBeforeTheError() throws Exception {
        start();
        ScriptedPeer peer = harness.connect();
        RegisteredTunnel tunnel = establish(peer);

        try (Socket visitor = visitor(tunnel)) {
            peer.expect(Frame.Open.class, TIMEOUT);
            Future<Void> stopped = harness.edge.stop(Duration.ofSeconds(15));

            assertThat(peer.expect(Frame.Close.class, TIMEOUT)).isEqualTo(Frame.Close.reset(1, ErrorCode.CANCELLED));
            peer.expectError(ErrorCode.SERVER_SHUTTING_DOWN, TIMEOUT);
            stopped.get(20, TimeUnit.SECONDS);
            assertThat(harness.ports.snapshot().reserved()).isZero();
        }
    }

    @Test
    void noOtherSocketCanShareAnOpenTunnelPort() throws Exception {
        start();
        RegisteredTunnel tunnel = establish(harness.connect());

        try (ServerSocket hijacker = new ServerSocket()) {
            hijacker.setReuseAddress(true);
            assertThatThrownBy(() ->
                            hijacker.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), tunnel.publicPort())))
                    .isInstanceOf(BindException.class);
        }
    }

    @Test
    void aChosenPortInUseOnTheHostIsQuarantinedAndAnotherIsUsed() throws Exception {
        int occupied = FIRST_PORT + 20;
        harness = new TunnelHarness(
                PortRange.parse(occupied + "-" + (occupied + 1)),
                alwaysFirst(),
                Duration.ofSeconds(15),
                Duration.ofSeconds(45));

        try (ServerSocket occupant = new ServerSocket(occupied, 50, InetAddress.getLoopbackAddress())) {
            RegisteredTunnel tunnel = establish(harness.connect());

            assertThat(tunnel.publicPort()).isEqualTo(occupied + 1);
            assertThat(harness.ports.snapshot().quarantined()).isOne();
            assertThat(harness.ports.snapshot().reserved()).isOne();
        }
    }

    @Test
    void aRequestedPortInUseOnTheHostIsRefusedWithPortUnavailable() throws Exception {
        start();
        int occupied = FIRST_PORT + 3;

        try (ServerSocket occupant = new ServerSocket(occupied, 50, InetAddress.getLoopbackAddress())) {
            ScriptedPeer peer = harness.connect();
            TunnelHarness.register(peer, occupied);

            assertThat(peer.expectError(ErrorCode.PORT_UNAVAILABLE, TIMEOUT).ref())
                    .isEqualTo("default");
        }
        awaitTrue(() -> harness.ports.snapshot().reserved() == 0);
        assertThat(harness.ports.snapshot().quarantined()).isZero();
    }

    private void start() {
        harness = new TunnelHarness(
                PortRange.parse(FIRST_PORT + "-" + (FIRST_PORT + POOL_SIZE - 1)),
                new Random(),
                Duration.ofSeconds(15),
                Duration.ofSeconds(45));
    }

    private List<Channel> openVisitors() throws Exception {
        SessionContext session = harness.onlySession();
        return TunnelHarness.onLoop(
                session, () -> ((StreamRelay) session.streams().orElseThrow()).visitorChannels());
    }

    private static Socket visitor(RegisteredTunnel tunnel) throws IOException {
        Socket socket = new Socket(InetAddress.getLoopbackAddress(), tunnel.publicPort());
        socket.setSoTimeout((int) TIMEOUT.toMillis());
        return socket;
    }

    private static void write(Socket socket, String text) throws IOException {
        socket.getOutputStream().write(text.getBytes(StandardCharsets.US_ASCII));
        socket.getOutputStream().flush();
    }

    private static Frame.Data data(String text) {
        return new Frame.Data(1, Unpooled.copiedBuffer(text, StandardCharsets.US_ASCII));
    }

    private RegisteredTunnel establish(ScriptedPeer peer) {
        RegisteredTunnel tunnel = harness.establish(peer, null);
        peer.autoGrant();
        return tunnel;
    }

    private static byte[] readData(ScriptedPeer peer, int length) {
        return readData(peer, 1, length);
    }

    /** Collects {@code length} payload bytes from {@code DATA} frames on {@code streamId}, each at most 64 KiB. */
    private static byte[] readData(ScriptedPeer peer, int streamId, int length) {
        ByteArrayOutputStream received = new ByteArrayOutputStream(length);
        while (received.size() < length) {
            Frame.Data data = peer.expect(Frame.Data.class, TIMEOUT);
            try {
                assertThat(data.streamId()).isEqualTo(streamId);
                ByteBuf payload = data.payload();
                assertThat(payload.readableBytes()).isBetween(1, FrameLimits.MAX_DATA_PAYLOAD);
                byte[] bytes = new byte[payload.readableBytes()];
                payload.readBytes(bytes);
                received.writeBytes(bytes);
            } finally {
                data.release();
            }
        }
        assertThat(received.size()).isEqualTo(length);
        return received.toByteArray();
    }

    private static byte[] readFully(InputStream in, int length) throws IOException {
        byte[] bytes = in.readNBytes(length);
        assertThat(bytes).hasSize(length);
        return bytes;
    }

    private static byte[] randomBytes(long seed) {
        byte[] bytes = new byte[TRANSFER];
        new Random(seed).nextBytes(bytes);
        return bytes;
    }

    private static byte[] sha256(byte[] bytes) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(bytes);
    }

    /** Always picks the first free port, so the test knows which port the pool will try. */
    private static RandomGenerator alwaysFirst() {
        return () -> 0L;
    }
}
