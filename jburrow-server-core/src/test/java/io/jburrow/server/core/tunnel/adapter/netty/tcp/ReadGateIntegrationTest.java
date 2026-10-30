package io.jburrow.server.core.tunnel.adapter.netty.tcp;

import static io.jburrow.server.core.tunnel.adapter.netty.tcp.BackpressureIntegrationTest.randomBytes;
import static io.jburrow.server.core.tunnel.adapter.netty.tcp.TunnelHarness.TIMEOUT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import io.jburrow.protocol.adapter.netty.Frame;
import io.jburrow.protocol.control.RegisteredTunnel;
import io.jburrow.protocol.control.Windows;
import io.jburrow.protocol.flow.FlowLimits;
import io.jburrow.protocol.testsupport.ScriptedPeer;
import io.jburrow.server.core.portpool.domain.PortRange;
import io.jburrow.server.core.tunnel.adapter.netty.control.SessionContext;
import io.jburrow.server.core.tunnel.adapter.netty.edge.EdgeTransport;
import io.netty.channel.Channel;
import io.netty.channel.ChannelOutboundBuffer;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.concurrent.ScheduledFuture;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * {@code docs/phase-2-multiplexing/ARCHITECTURE.md} §Visitor to client: the read gate, with many real visitors racing
 * for one session's credit: the one-byte carry, the cap at the session channel's headroom, turns, and a {@code FIN}
 * behind held-back bytes. On both transports, since the carry exists for epoll's sake.
 */
class ReadGateIntegrationTest {

    private static final String TRANSPORTS = "io.jburrow.server.core.tunnel.adapter.netty.support.Transports#available";
    private static final int FIRST_PORT = 30_400;
    private static final int KIB = 1024;
    private static final int WINDOW = FlowLimits.MIN_STREAM_WINDOW;
    private static final Windows SMALLEST = new Windows(WINDOW, WINDOW);
    private static final Duration QUIET = Duration.ofMillis(300);

    private final ExecutorService threads = Executors.newVirtualThreadPerTaskExecutor();
    private final List<Socket> visitors = new ArrayList<>();
    private TunnelHarness harness;
    /** {@code DATA} bytes the test has taken from the peer, which its ledger may count ahead of. */
    private long taken;
    /** {@code DATA} frames the test has taken. */
    private long frames;

    @AfterEach
    void stop() throws Exception {
        for (Socket visitor : visitors) {
            visitor.close();
        }
        if (harness != null) {
            harness.close();
            assertThat(harness.streamQuota.inUse()).isZero();
        }
        threads.shutdownNow();
    }

    @ParameterizedTest
    @MethodSource(TRANSPORTS)
    void fiftyVisitorsRacingForOneSessionWindowCarryAtMostOneByteEachAndLoseNothing(EdgeTransport transport)
            throws Exception {
        start(transport);
        ScriptedPeer peer = harness.connect();
        RegisteredTunnel tunnel = harness.establish(peer, null, SMALLEST);
        int count = 50;
        List<byte[]> payloads = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            open(peer, tunnel, i + 1);
            payloads.add(randomBytes(WINDOW, 100 + i));
        }

        writeAllAtOnce(payloads);
        Map<Integer, ByteArrayOutputStream> received = new HashMap<>();
        takeData(peer, WINDOW, received);
        assertNothingMore(peer);

        SessionContext session = harness.onlySession();
        List<Integer> carried = TunnelHarness.onLoop(session, () -> carried(session));
        assertThat(carried).allSatisfy(bytes -> assertThat(bytes).isBetween(0, 1));
        assertThat(carried.stream().mapToInt(Integer::intValue).sum()).isLessThanOrEqualTo(count);

        long expected = (long) count * WINDOW;
        while (taken < expected) {
            peer.grantSession(WINDOW);
            takeData(peer, Math.min(expected, taken + WINDOW), received);
        }
        for (int i = 0; i < count; i++) {
            assertThat(received.get(i + 1).toByteArray()).as("stream %d", i + 1).isEqualTo(payloads.get(i));
        }
    }

    @ParameterizedTest
    @MethodSource(TRANSPORTS)
    void aClientThatStopsReadingLeavesTheSessionChannelAtMostOneFrameOverItsHighWatermark(EdgeTransport transport)
            throws Exception {
        start(transport);
        ScriptedPeer peer = harness.connect();
        RegisteredTunnel tunnel =
                harness.establish(peer, null, new Windows(FlowLimits.MAX_WINDOW, FlowLimits.MAX_WINDOW));
        int count = 20;
        int each = 1024 * KIB;
        for (int i = 0; i < count; i++) {
            open(peer, tunnel, i + 1);
        }
        SessionContext session = harness.onlySession();
        Channel channel = session.channel();
        AtomicLong mostPending = new AtomicLong();
        ScheduledFuture<?> sampling = channel.eventLoop()
                .scheduleAtFixedRate(
                        () -> {
                            ChannelOutboundBuffer buffer = channel.unsafe().outboundBuffer();
                            if (buffer != null) {
                                mostPending.accumulateAndGet(buffer.totalPendingWriteBytes(), Math::max);
                            }
                        },
                        0,
                        2,
                        TimeUnit.MILLISECONDS);
        peer.pauseReading();

        List<byte[]> payloads = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            payloads.add(new byte[each]);
        }
        writeAllAtOnce(payloads);
        TimeUnit.SECONDS.sleep(2);
        sampling.cancel(false);

        assertThat(mostPending.get())
                .as("the high watermark, one frame and a little for control frames and framing")
                .isLessThanOrEqualTo(TunnelHarness.SESSION_WRITE_HIGH + FlowLimits.MIN_STREAM_WINDOW + 4 * KIB);
        peer.resumeReading();
        takeData(peer, (long) count * each, new HashMap<>());
    }

    @ParameterizedTest
    @MethodSource(TRANSPORTS)
    void visitorsWaitingForTheSessionTakeTurnsAtItsCredit(EdgeTransport transport) throws Exception {
        start(transport);
        ScriptedPeer peer = harness.connect();
        RegisteredTunnel tunnel = harness.establish(peer, null, SMALLEST);
        int count = 10;
        List<byte[]> payloads = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            open(peer, tunnel, i + 1);
            peer.grant(i + 1, 64 * 1024 * KIB);
            payloads.add(new byte[16 * 1024 * KIB]);
        }
        writeAllAtOnce(payloads);
        Map<Integer, ByteArrayOutputStream> received = new HashMap<>();
        takeData(peer, WINDOW, received);
        for (int grant = 0; grant < 20; grant++) {
            peer.grantSession(WINDOW);
            takeData(peer, taken + WINDOW, received);
        }

        Map<Integer, Long> shares = new HashMap<>();
        int grants = 50;
        for (int grant = 0; grant < grants; grant++) {
            Map<Integer, ByteArrayOutputStream> thisGrant = new HashMap<>();
            peer.grantSession(WINDOW);
            takeData(peer, taken + WINDOW, thisGrant);
            thisGrant.forEach((id, bytes) -> shares.merge(id, (long) bytes.size(), Long::sum));
        }

        long fair = (long) grants * WINDOW / count;
        assertThat(shares).hasSize(count);
        assertThat(shares.values()).allSatisfy(share -> assertThat(share).isBetween(fair / 2, fair * 3 / 2));
    }

    @ParameterizedTest
    @MethodSource(TRANSPORTS)
    void aHundredVisitorsInLineStillGetFramesOfAboutTheShareFloor(EdgeTransport transport) throws Exception {
        start(transport);
        ScriptedPeer peer = harness.connect();
        RegisteredTunnel tunnel = harness.establish(peer, null, SMALLEST);
        int count = 100;
        List<byte[]> payloads = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            open(peer, tunnel, i + 1);
            peer.grant(i + 1, 64 * 1024 * KIB);
            payloads.add(new byte[256 * KIB]);
        }
        writeAllAtOnce(payloads);
        takeData(peer, WINDOW, new HashMap<>());
        for (int grant = 0; grant < 5; grant++) {
            peer.grantSession(WINDOW);
            takeData(peer, taken + WINDOW, new HashMap<>());
        }

        int grants = 20;
        long framesBefore = frames;
        for (int grant = 0; grant < grants; grant++) {
            peer.grantSession(WINDOW);
            takeData(peer, taken + WINDOW, new HashMap<>());
        }

        long average = (long) grants * WINDOW / (frames - framesBefore);
        assertThat(average)
                .as("an equal share alone would be %d bytes", WINDOW / count)
                .isGreaterThanOrEqualTo(ReadGate.SHARE_FLOOR / 4);
    }

    @ParameterizedTest
    @MethodSource(TRANSPORTS)
    void aFinBehindACarriedByteArrivesAfterItOnceCreditReturns(EdgeTransport transport) throws Exception {
        start(transport);
        ScriptedPeer peer = harness.connect();
        RegisteredTunnel tunnel = harness.establish(peer, null, SMALLEST);
        Socket spender = open(peer, tunnel, 1);
        Socket closer = open(peer, tunnel, 2);
        spender.getOutputStream().write(new byte[WINDOW]);
        takeData(peer, WINDOW, new HashMap<>());

        closer.getOutputStream().write(7);
        closer.shutdownOutput();
        assertThatExceptionOfType(AssertionError.class)
                .as("neither the byte nor the CLOSE goes before credit")
                .isThrownBy(() -> peer.expect(Frame.class, QUIET));
        peer.grantSession(WINDOW);

        Frame.Data data = peer.expect(Frame.Data.class, TIMEOUT);
        assertThat(data.streamId()).isEqualTo(2);
        assertThat(data.payload().readableBytes()).isOne();
        assertThat(data.payload().readByte()).isEqualTo((byte) 7);
        data.release();
        assertThat(peer.expect(Frame.Close.class, TIMEOUT)).isEqualTo(Frame.Close.half(2));
    }

    /**
     * On epoll a visitor's {@code FIN} makes Netty read it to the end at once, credit or not. What it reads past its
     * credit is held in order and sent as credit returns, and the {@code CLOSE} follows its last byte.
     */
    @ParameterizedTest
    @MethodSource(TRANSPORTS)
    void aVisitorThatSendsFinWhileHeldBackLosesNothingAndClosesAfterItsLastByte(EdgeTransport transport)
            throws Exception {
        start(transport);
        ScriptedPeer peer = harness.connect();
        RegisteredTunnel tunnel = harness.establish(peer, null, SMALLEST);
        Socket spender = open(peer, tunnel, 1);
        Socket closer = open(peer, tunnel, 2);
        peer.grant(2, 1024 * KIB);
        spender.getOutputStream().write(new byte[WINDOW]);
        takeData(peer, WINDOW, new HashMap<>());
        byte[] payload = randomBytes(200 * KIB, 42);

        closer.getOutputStream().write(payload);
        closer.shutdownOutput();
        TimeUnit.MILLISECONDS.sleep(QUIET.toMillis());

        Map<Integer, ByteArrayOutputStream> received = new HashMap<>();
        while (taken < WINDOW + payload.length) {
            peer.grantSession(WINDOW);
            takeData(peer, Math.min(WINDOW + payload.length, taken + WINDOW), received);
        }
        assertThat(received.keySet()).containsExactly(2);
        assertThat(received.get(2).toByteArray()).isEqualTo(payload);
        assertThat(peer.expect(Frame.Close.class, TIMEOUT)).isEqualTo(Frame.Close.half(2));
    }

    private void start(EdgeTransport transport) {
        harness = new TunnelHarness(
                PortRange.parse(FIRST_PORT + "-" + (FIRST_PORT + 9)),
                new Random(),
                TunnelHarness.Settings.defaults().transport(transport));
    }

    /** Connects a visitor and takes its {@code OPEN}, which must be for {@code streamId}. */
    private Socket open(ScriptedPeer peer, RegisteredTunnel tunnel, int streamId) throws IOException {
        Socket visitor = new Socket(InetAddress.getLoopbackAddress(), tunnel.publicPort());
        visitor.setSoTimeout((int) TIMEOUT.toMillis());
        visitors.add(visitor);
        assertThat(peer.expect(Frame.Open.class, TIMEOUT).streamId()).isEqualTo(streamId);
        return visitor;
    }

    /** Each visitor writes its payload from its own thread, all released at the same moment. */
    private void writeAllAtOnce(List<byte[]> payloads) {
        CountDownLatch go = new CountDownLatch(1);
        for (int i = 0; i < payloads.size(); i++) {
            Socket visitor = visitors.get(i);
            byte[] payload = payloads.get(i);
            threads.submit(() -> {
                go.await();
                visitor.getOutputStream().write(payload);
                return null;
            });
        }
        go.countDown();
    }

    /**
     * Takes {@code DATA} until {@code total} bytes have been taken in all, adding each payload to its stream's bytes.
     * Counts what it takes rather than the peer's ledger, which runs ahead of the frames still queued.
     */
    private void takeData(ScriptedPeer peer, long total, Map<Integer, ByteArrayOutputStream> received) {
        while (taken < total) {
            Frame frame = peer.expect(Frame.class, TIMEOUT);
            if (frame instanceof Frame.Data data) {
                byte[] bytes = new byte[data.payload().readableBytes()];
                data.payload().readBytes(bytes);
                taken += bytes.length;
                frames++;
                received.computeIfAbsent(data.streamId(), id -> new ByteArrayOutputStream())
                        .writeBytes(bytes);
            }
            ReferenceCountUtil.release(frame);
        }
        assertThat(taken).isEqualTo(total);
    }

    private static void assertNothingMore(ScriptedPeer peer) {
        assertThatExceptionOfType(AssertionError.class)
                .isThrownBy(() -> peer.expect(Frame.Data.class, QUIET))
                .withMessageContaining("got nothing");
    }

    /** The bytes each open stream carries; on the session's event loop. */
    private static List<Integer> carried(SessionContext session) {
        StreamRelay relay = TunnelHarness.relay(session);
        return relay.visitorChannels().stream()
                .map(channel -> channel.pipeline().get(VisitorHandler.class).carried())
                .toList();
    }
}
