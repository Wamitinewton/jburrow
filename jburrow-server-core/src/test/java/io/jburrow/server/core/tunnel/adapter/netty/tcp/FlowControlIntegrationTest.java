package io.jburrow.server.core.tunnel.adapter.netty.tcp;

import static io.jburrow.server.core.tunnel.adapter.netty.tcp.BackpressureIntegrationTest.randomBytes;
import static io.jburrow.server.core.tunnel.adapter.netty.tcp.BackpressureIntegrationTest.sha256;
import static io.jburrow.server.core.tunnel.adapter.netty.tcp.TunnelHarness.TIMEOUT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import io.jburrow.protocol.adapter.netty.Frame;
import io.jburrow.protocol.control.ClientInfo;
import io.jburrow.protocol.control.ErrorBody;
import io.jburrow.protocol.control.Hello;
import io.jburrow.protocol.control.RegisteredTunnel;
import io.jburrow.protocol.control.Windows;
import io.jburrow.protocol.testsupport.ScriptedPeer;
import io.jburrow.protocol.wire.ErrorCode;
import io.jburrow.protocol.wire.FrameLimits;
import io.jburrow.protocol.wire.StreamIds;
import io.jburrow.server.core.portpool.domain.PortRange;
import io.jburrow.server.core.tunnel.adapter.netty.edge.EdgeTransport;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.websocketx.WebSocketCloseStatus;
import io.netty.util.ReferenceCountUtil;
import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The server's side of {@code docs/protocol.md} §10 over real sockets, with {@link ScriptedPeer} playing the client and
 * policing every window it announces: the server sends no more than the client's credit, returns its own credit only as
 * bytes leave it, and treats every overrun as {@code FLOW_CONTROL_ERROR}.
 */
class FlowControlIntegrationTest {

    private static final String TRANSPORTS = "io.jburrow.server.core.tunnel.adapter.netty.support.Transports#available";
    private static final int FIRST_PORT = 30_300;
    private static final int KIB = 1024;
    private static final int STREAM_WINDOW = ScriptedPeer.CLIENT_WINDOWS.stream();
    private static final Duration QUIET = Duration.ofMillis(300);
    private static final ClientInfo CLIENT = new ClientInfo("jburrow-test", "1.0", "linux", "amd64");

    private final ExecutorService threads = Executors.newVirtualThreadPerTaskExecutor();
    private TunnelHarness harness;
    /** {@code DATA} bytes the test has taken from the peer, which its ledger may count ahead of. */
    private long taken;

    @AfterEach
    void stop() throws Exception {
        if (harness != null) {
            harness.close();
            assertThat(harness.streamQuota.inUse()).isZero();
        }
        threads.shutdownNow();
    }

    // What the server sends.

    @ParameterizedTest
    @MethodSource(TRANSPORTS)
    void theServerSendsOneStreamWindowAndNoMoreUntilTheClientGrantsCredit(EdgeTransport transport) throws Exception {
        start(transport);
        ScriptedPeer peer = harness.connect();
        RegisteredTunnel tunnel = harness.establish(peer, null);

        try (Socket visitor = visitor(tunnel)) {
            peer.expect(Frame.Open.class, TIMEOUT);
            Future<?> upload = writeInBackground(visitor, 32 * 1024 * KIB);

            takeData(peer, STREAM_WINDOW);
            assertNothingMore(peer);
            peer.grant(1, 128 * KIB);
            takeData(peer, STREAM_WINDOW + 128 * KIB);
            assertNothingMore(peer);

            assertThat(peer.dataReceived(1)).isEqualTo(STREAM_WINDOW + 128 * KIB);
            assertThat(upload.isDone()).as("the visitor's writes are held back").isFalse();
        }
    }

    @ParameterizedTest
    @MethodSource(TRANSPORTS)
    void theSessionWindowCapsEveryStreamTogetherAndStreamZeroCreditResumesThem(EdgeTransport transport)
            throws Exception {
        start(transport);
        ScriptedPeer peer = harness.connect();
        RegisteredTunnel tunnel = harness.establish(peer, null, new Windows(STREAM_WINDOW, 384 * KIB));

        try (Socket first = visitor(tunnel);
                Socket second = visitor(tunnel)) {
            peer.expect(Frame.Open.class, TIMEOUT);
            peer.expect(Frame.Open.class, TIMEOUT);
            writeInBackground(first, 512 * KIB);
            writeInBackground(second, 512 * KIB);

            takeData(peer, 384 * KIB);
            assertNothingMore(peer);
            peer.grantSession(256 * KIB);
            takeData(peer, 2 * STREAM_WINDOW);
            assertNothingMore(peer);

            assertThat(peer.dataReceived(1)).isEqualTo(STREAM_WINDOW);
            assertThat(peer.dataReceived(2)).isEqualTo(STREAM_WINDOW);
        }
    }

    @ParameterizedTest
    @MethodSource(TRANSPORTS)
    void sixtyFourMebibytesFromAVisitorArriveIntactWhenTheClientReturnsCredit(EdgeTransport transport)
            throws Exception {
        start(transport);
        ScriptedPeer peer = harness.connect();
        RegisteredTunnel tunnel = harness.establish(peer, null);
        peer.autoGrant();
        byte[] payload = randomBytes(64 * 1024 * KIB, 7);

        try (Socket visitor = visitor(tunnel)) {
            peer.expect(Frame.Open.class, TIMEOUT);
            Future<?> upload = threads.submit(() -> {
                visitor.getOutputStream().write(payload);
                return null;
            });

            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long received = 0;
            while (received < payload.length) {
                Frame.Data data = peer.expect(Frame.Data.class, TIMEOUT);
                received += data.payload().readableBytes();
                digest.update(data.payload().nioBuffer());
                data.release();
            }
            upload.get(30, TimeUnit.SECONDS);
            assertThat(digest.digest()).isEqualTo(sha256(payload));
        }
    }

    @ParameterizedTest
    @MethodSource(TRANSPORTS)
    void anOpenWithOptimisticDataCarriesNoMoreThanTheClientsStreamWindow(EdgeTransport transport) throws Exception {
        start(transport);
        ScriptedPeer peer = harness.connect();
        RegisteredTunnel tunnel = harness.establish(peer, null, new Windows(64 * KIB, 1024 * KIB));

        try (Socket visitor = visitor(tunnel)) {
            visitor.getOutputStream().write(new byte[1024 * KIB / 4]);
            peer.expect(Frame.Open.class, TIMEOUT);

            takeData(peer, 64 * KIB);
            assertNothingMore(peer);
        }
    }

    // What the server returns.

    @ParameterizedTest
    @MethodSource(TRANSPORTS)
    void theServerReturnsCreditAtHalfItsWindowsAsItWritesToTheVisitor(EdgeTransport transport) throws Exception {
        start(transport);
        ScriptedPeer peer = harness.connect();
        RegisteredTunnel tunnel = harness.establish(peer, null);
        byte[] payload = randomBytes(TunnelHarness.SERVER_WINDOWS.stream(), 8);

        try (Socket visitor = visitor(tunnel)) {
            peer.expect(Frame.Open.class, TIMEOUT);
            sendAll(peer, 1, payload);

            assertThat(sha256(visitor.getInputStream().readNBytes(payload.length)))
                    .isEqualTo(sha256(payload));
            List<Frame.WindowUpdate> updates = List.of(
                    peer.expect(Frame.WindowUpdate.class, TIMEOUT),
                    peer.expect(Frame.WindowUpdate.class, TIMEOUT),
                    peer.expect(Frame.WindowUpdate.class, TIMEOUT));
            assertThat(updates)
                    .containsExactlyInAnyOrder(
                            new Frame.WindowUpdate(1, 128 * KIB),
                            new Frame.WindowUpdate(1, 128 * KIB),
                            new Frame.WindowUpdate(StreamIds.CONTROL, 256 * KIB));
            assertThatExceptionOfType(AssertionError.class)
                    .isThrownBy(() -> peer.expect(Frame.WindowUpdate.class, QUIET));
        }
    }

    @ParameterizedTest
    @MethodSource(TRANSPORTS)
    void aVisitorThatDoesntReadStopsOnlyItsOwnStreamsCredit(EdgeTransport transport) throws Exception {
        start(transport);
        ScriptedPeer peer = harness.connect();
        RegisteredTunnel tunnel = harness.establish(peer, null);
        peer.ignoreWindowUpdates();
        byte[] flowing = randomBytes(4 * 1024 * KIB, 9);

        try (Socket stalled = visitor(tunnel);
                Socket reading = visitor(tunnel)) {
            peer.expect(Frame.Open.class, TIMEOUT);
            peer.expect(Frame.Open.class, TIMEOUT);
            AtomicLong sentToStalled = new AtomicLong();
            Future<?> stalling = threads.submit(() -> sendUntilNoCredit(peer, 1, sentToStalled));

            stalling.get(60, TimeUnit.SECONDS);
            assertThat(sentToStalled.get()).isGreaterThanOrEqualTo(STREAM_WINDOW);
            assertThat(peer.peerStreamCredit(1)).isLessThan(FrameLimits.MAX_DATA_PAYLOAD);

            Future<?> toReading = threads.submit(() -> {
                sendAll(peer, 2, flowing);
                return null;
            });
            assertThat(sha256(reading.getInputStream().readNBytes(flowing.length)))
                    .isEqualTo(sha256(flowing));
            toReading.get(30, TimeUnit.SECONDS);
            assertThat(peer.peerStreamCredit(1)).isLessThan(FrameLimits.MAX_DATA_PAYLOAD);
        }
    }

    @ParameterizedTest
    @MethodSource(TRANSPORTS)
    void dataForAStreamResetUnderItStillReturnsItsSessionCredit(EdgeTransport transport) throws Exception {
        start(transport);
        ScriptedPeer peer = harness.connect();
        RegisteredTunnel tunnel = harness.establish(peer, null);
        Socket visitor = visitor(tunnel);
        peer.expect(Frame.Open.class, TIMEOUT);

        sendAll(peer, 1, new byte[192 * KIB]);
        visitor.setSoLinger(true, 0);
        visitor.close();
        long sessionCredit = 0;
        Frame frame = peer.expect(Frame.class, TIMEOUT);
        while (!(frame instanceof Frame.Close)) {
            sessionCredit += sessionIncrement(frame);
            frame = peer.expect(Frame.class, TIMEOUT);
        }
        assertThat(frame).isEqualTo(Frame.Close.reset(1, ErrorCode.PEER_RESET));

        for (int i = 0; i < 5; i++) {
            peer.send(new Frame.Data(1, Unpooled.wrappedBuffer(new byte[64 * KIB])));
        }
        while (sessionCredit < 512 * KIB) {
            sessionCredit += sessionIncrement(peer.expect(Frame.class, TIMEOUT));
        }
        assertThat(sessionCredit).isEqualTo(512 * KIB);
    }

    // Violations.

    @ParameterizedTest
    @MethodSource(TRANSPORTS)
    void dataPastAStreamWindowIsAFlowControlError(EdgeTransport transport) throws Exception {
        start(transport);
        ScriptedPeer peer = harness.connect();
        RegisteredTunnel tunnel = harness.establish(peer, null);
        peer.ignoreWindowUpdates();

        try (Socket visitor = visitor(tunnel)) {
            peer.expect(Frame.Open.class, TIMEOUT);
            flood(peer, List.of(1), false);

            assertThat(expectFlowControlError(peer).message()).contains("stream window");
        }
    }

    @ParameterizedTest
    @MethodSource(TRANSPORTS)
    void dataPastTheSessionWindowIsAFlowControlError(EdgeTransport transport) throws Exception {
        start(transport);
        ScriptedPeer peer = harness.connect();
        RegisteredTunnel tunnel = harness.establish(peer, null);
        peer.ignoreWindowUpdates();
        List<Socket> visitors = new ArrayList<>();
        List<Integer> streams = new ArrayList<>();

        try {
            for (int i = 1; i <= 10; i++) {
                visitors.add(visitor(tunnel));
                streams.add(peer.expect(Frame.Open.class, TIMEOUT).streamId());
            }
            flood(peer, streams, true);

            assertThat(expectFlowControlError(peer).message()).contains("session window");
        } finally {
            for (Socket visitor : visitors) {
                visitor.close();
            }
        }
    }

    @ParameterizedTest
    @MethodSource(TRANSPORTS)
    void creditPastTheLargestWindowIsAFlowControlErrorOnAStreamAndOnTheSession(EdgeTransport transport)
            throws Exception {
        start(transport);
        for (int streamId : new int[] {1, StreamIds.CONTROL}) {
            ScriptedPeer peer = harness.connect();
            RegisteredTunnel tunnel = harness.establish(peer, null);
            try (Socket visitor = visitor(tunnel)) {
                int opened = peer.expect(Frame.Open.class, TIMEOUT).streamId();

                peer.send(new Frame.WindowUpdate(
                        streamId == StreamIds.CONTROL ? StreamIds.CONTROL : opened, Integer.MAX_VALUE));

                expectFlowControlError(peer);
            }
        }
    }

    @ParameterizedTest
    @MethodSource(TRANSPORTS)
    void aWindowUpdateBeforeTheSessionIsEstablishedIsAProtocolError(EdgeTransport transport) {
        start(transport);
        ScriptedPeer peer = harness.connect();
        peer.send(new Frame.Hello(new Hello(CLIENT, List.of(), ScriptedPeer.CLIENT_WINDOWS)));
        peer.expect(Frame.ServerHello.class, TIMEOUT);

        peer.send(new Frame.WindowUpdate(StreamIds.CONTROL, 1));

        peer.expectError(ErrorCode.PROTOCOL_ERROR, TIMEOUT);
    }

    @ParameterizedTest
    @MethodSource(TRANSPORTS)
    void aWindowUpdateForAStreamNeverOpenedIsAProtocolError(EdgeTransport transport) {
        start(transport);
        ScriptedPeer peer = harness.connect();
        harness.establish(peer, null);

        peer.send(new Frame.WindowUpdate(5, 1));

        peer.expectError(ErrorCode.PROTOCOL_ERROR, TIMEOUT);
    }

    @ParameterizedTest
    @MethodSource(TRANSPORTS)
    void aWindowUpdateForAClosedStreamIsIgnoredAndTheSessionGoesOn(EdgeTransport transport) throws Exception {
        start(transport);
        ScriptedPeer peer = harness.connect();
        RegisteredTunnel tunnel = harness.establish(peer, null);
        Socket first = visitor(tunnel);
        peer.expect(Frame.Open.class, TIMEOUT);
        first.setSoLinger(true, 0);
        first.close();
        assertThat(peer.expect(Frame.Close.class, TIMEOUT)).isEqualTo(Frame.Close.reset(1, ErrorCode.PEER_RESET));

        peer.send(new Frame.WindowUpdate(1, 1_000));

        try (Socket second = visitor(tunnel)) {
            assertThat(peer.expect(Frame.Open.class, TIMEOUT).streamId()).isEqualTo(2);
            second.getOutputStream().write(new byte[] {42});
            Frame.Data data = peer.expect(Frame.Data.class, TIMEOUT);
            assertThat(data.payload().readByte()).isEqualTo((byte) 42);
            data.release();
        }
    }

    /** The session credit {@code frame} returns, if it is a {@code WINDOW_UPDATE} on stream 0. Releases it. */
    private static int sessionIncrement(Frame frame) {
        ReferenceCountUtil.release(frame);
        return frame instanceof Frame.WindowUpdate update && update.streamId() == StreamIds.CONTROL
                ? update.increment()
                : 0;
    }

    private void start(EdgeTransport transport) {
        harness = new TunnelHarness(
                PortRange.parse(FIRST_PORT + "-" + (FIRST_PORT + 9)),
                new Random(),
                TunnelHarness.Settings.defaults().transport(transport));
    }

    private Future<?> writeInBackground(Socket visitor, int bytes) {
        return threads.submit(() -> {
            visitor.getOutputStream().write(new byte[bytes]);
            return null;
        });
    }

    /** Takes {@code DATA} frames, releasing each, until {@code total} bytes have been taken on every stream together. */
    private void takeData(ScriptedPeer peer, long total) {
        while (taken < total) {
            Frame.Data data = peer.expect(Frame.Data.class, TIMEOUT);
            taken += data.payload().readableBytes();
            data.release();
        }
        assertThat(taken).isEqualTo(total);
        assertThat(peer.dataReceived()).isEqualTo(total);
    }

    /** Nothing more arrives for a while: no {@code DATA} past the credit, which the peer would also have reported. */
    private static void assertNothingMore(ScriptedPeer peer) {
        assertThatExceptionOfType(AssertionError.class)
                .isThrownBy(() -> peer.expect(Frame.Data.class, QUIET))
                .withMessageContaining("got nothing");
    }

    private static void sendAll(ScriptedPeer peer, int streamId, byte[] bytes) {
        for (int offset = 0; offset < bytes.length; offset += FrameLimits.MAX_DATA_PAYLOAD) {
            int length = Math.min(FrameLimits.MAX_DATA_PAYLOAD, bytes.length - offset);
            peer.sendData(streamId, Unpooled.wrappedBuffer(bytes, offset, length), TIMEOUT);
        }
    }

    /** Sends full frames on {@code streamId} as credit allows, until none comes for a second. */
    private static Void sendUntilNoCredit(ScriptedPeer peer, int streamId, AtomicLong sent) {
        while (true) {
            try {
                peer.sendData(
                        streamId,
                        Unpooled.wrappedBuffer(new byte[FrameLimits.MAX_DATA_PAYLOAD]),
                        QUIET.multipliedBy(4));
            } catch (AssertionError noCredit) {
                return null;
            }
            sent.addAndGet(FrameLimits.MAX_DATA_PAYLOAD);
        }
    }

    /**
     * Sends full frames round the {@code streams}, past what the server allows, until it ends the session or no stream
     * has had credit for two seconds. With {@code withinStreamCredit}, a stream only gets a frame its credit covers in
     * this peer's view, which the server's is never below, so only the session window can be overrun: once the
     * visitors' sockets are full, ten streams each hold more than 64 KiB the server can't release, past its 512 KiB.
     */
    private static void flood(ScriptedPeer peer, List<Integer> streams, boolean withinStreamCredit)
            throws InterruptedException {
        long limit = 256L * 1024 * KIB;
        long sent = 0;
        int idle = 0;
        try {
            while (sent < limit && idle < 40) {
                boolean any = false;
                for (int streamId : streams) {
                    if (withinStreamCredit && peer.peerStreamCredit(streamId) < FrameLimits.MAX_DATA_PAYLOAD) {
                        continue;
                    }
                    peer.send(new Frame.Data(streamId, Unpooled.wrappedBuffer(new byte[FrameLimits.MAX_DATA_PAYLOAD])));
                    sent += FrameLimits.MAX_DATA_PAYLOAD;
                    any = true;
                }
                if (any) {
                    idle = 0;
                } else {
                    idle++;
                    TimeUnit.MILLISECONDS.sleep(50);
                }
            }
        } catch (IllegalStateException connectionClosed) {
            return;
        }
        if (sent >= limit) {
            throw new AssertionError("The server took " + sent + " bytes without ending the session");
        }
    }

    /** The session ends with {@code ERROR FLOW_CONTROL_ERROR} and close code 1002, after any streams' resets. */
    private static ErrorBody expectFlowControlError(ScriptedPeer peer) {
        while (true) {
            Frame frame = peer.expect(Frame.class, TIMEOUT);
            if (frame instanceof Frame.Error error) {
                assertThat(error.body().code()).isEqualTo(ErrorCode.FLOW_CONTROL_ERROR);
                assertThat(peer.expectClosed(TIMEOUT)).isEqualTo(WebSocketCloseStatus.PROTOCOL_ERROR);
                return error.body();
            }
            assertThat(frame).isInstanceOfAny(Frame.Close.class, Frame.WindowUpdate.class);
            ReferenceCountUtil.release(frame);
        }
    }

    private static Socket visitor(RegisteredTunnel tunnel) throws IOException {
        Socket socket = new Socket(InetAddress.getLoopbackAddress(), tunnel.publicPort());
        socket.setSoTimeout((int) TIMEOUT.multipliedBy(3).toMillis());
        return socket;
    }
}
