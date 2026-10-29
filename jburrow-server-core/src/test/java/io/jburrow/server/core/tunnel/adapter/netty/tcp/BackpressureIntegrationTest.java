package io.jburrow.server.core.tunnel.adapter.netty.tcp;

import static io.jburrow.server.core.tunnel.adapter.netty.tcp.TunnelHarness.TIMEOUT;
import static org.assertj.core.api.Assertions.assertThat;

import io.jburrow.protocol.adapter.netty.Frame;
import io.jburrow.protocol.control.RegisteredTunnel;
import io.jburrow.protocol.testsupport.ScriptedPeer;
import io.jburrow.protocol.wire.FrameLimits;
import io.jburrow.server.core.portpool.domain.PortRange;
import io.jburrow.server.core.tunnel.adapter.netty.edge.EdgeTransport;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * {@code docs/phase-2-multiplexing/ARCHITECTURE.md} §Head-of-line blocking and deadlock over real sockets: a slow
 * visitor holds back only its own stream, the client's connection is always read, and a client that stops reading
 * leaves the server's memory flat. Nothing is lost when reading resumes.
 */
class BackpressureIntegrationTest {

    private static final int FIRST_PORT = 30_200;
    private static final long MAX_DIRECT_MEMORY_GROWTH = 2L * 1024 * 1024;
    private static final Duration SETTLE = Duration.ofMillis(500);
    private static final Duration SEND_TIMEOUT = Duration.ofSeconds(60);

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

    @ParameterizedTest
    @MethodSource("io.jburrow.server.core.tunnel.adapter.netty.support.Transports#available")
    void aVisitorThatStopsReadingHoldsBackOnlyItsOwnStreamAndTheClientIsStillRead(EdgeTransport transport)
            throws Exception {
        Duration idleTimeout = Duration.ofSeconds(2);
        harness = start(transport, Duration.ofSeconds(1), idleTimeout);
        ScriptedPeer peer = harness.connect();
        RegisteredTunnel tunnel = harness.establish(peer, null);
        peer.autoGrant();
        peer.ignoreWindowUpdates();
        byte[] stalled = randomBytes(8 * 1024 * 1024, 4);
        byte[] flowing = randomBytes(16 * 1024 * 1024, 5);

        try (Socket slow = visitor(tunnel);
                Socket fast = visitor(tunnel)) {
            peer.expect(Frame.Open.class, TIMEOUT);
            peer.expect(Frame.Open.class, TIMEOUT);
            AtomicLong sentToSlow = new AtomicLong();
            Future<?> toSlow = threads.submit(() -> sendAll(peer, 1, stalled, sentToSlow));
            awaitStalled(sentToSlow);
            long stalledAt = sentToSlow.get();
            assertThat(stalledAt).isLessThan(stalled.length);

            Future<?> toFast = threads.submit(() -> sendAll(peer, 2, flowing, new AtomicLong()));
            assertThat(sha256(fast.getInputStream().readNBytes(flowing.length))).isEqualTo(sha256(flowing));
            toFast.get(30, TimeUnit.SECONDS);

            TimeUnit.MILLISECONDS.sleep(idleTimeout.multipliedBy(3).toMillis());
            assertThat(sentToSlow.get())
                    .as("the slow stream is still held back")
                    .isEqualTo(stalledAt);
            assertThat(harness.metrics.events())
                    .doesNotContain("sessionEnded HEARTBEAT_TIMEOUT")
                    .doesNotContain("backpressurePaused OUTBOUND");
            assertThat(harness.sessions.registry().active()).isOne();

            assertThat(sha256(slow.getInputStream().readNBytes(stalled.length))).isEqualTo(sha256(stalled));
            toSlow.get(30, TimeUnit.SECONDS);
        }
    }

    @ParameterizedTest
    @MethodSource("io.jburrow.server.core.tunnel.adapter.netty.support.Transports#available")
    void aClientThatStopsReadingLeavesServerMemoryFlatAndLosesNothing(EdgeTransport transport) throws Exception {
        harness = start(transport, Duration.ofSeconds(15), Duration.ofSeconds(45));
        ScriptedPeer peer = harness.connect();
        RegisteredTunnel tunnel = harness.establish(peer, null);
        peer.autoGrant();
        peer.ignoreWindowUpdates();
        int visitors = 4;
        int each = 16 * 1024 * 1024;

        List<Socket> sockets = new ArrayList<>();
        try {
            for (int i = 0; i < visitors; i++) {
                sockets.add(visitor(tunnel));
                peer.expect(Frame.Open.class, TIMEOUT);
            }
            warmUp(peer, sockets.getFirst());
            LongSupplier directMemory = harness.edge::usedDirectMemory;
            long before = directMemory.getAsLong();
            assertThat(before).as("direct memory is measured").isNotNegative();
            peer.pauseReading();

            AtomicLong written = new AtomicLong();
            List<byte[]> payloads = new ArrayList<>();
            List<Future<?>> uploads = new ArrayList<>();
            for (int i = 0; i < visitors; i++) {
                byte[] payload = randomBytes(each, 10 + i);
                payloads.add(payload);
                OutputStream out = sockets.get(i).getOutputStream();
                uploads.add(threads.submit(() -> {
                    writeInChunks(out, payload, written);
                    return null;
                }));
            }
            awaitStalled(written);
            assertThat(written.get()).isLessThan((long) visitors * each);
            assertThat(directMemory.getAsLong() - before).isLessThan(MAX_DIRECT_MEMORY_GROWTH);
            TimeUnit.MILLISECONDS.sleep(SETTLE.toMillis());
            assertThat(directMemory.getAsLong() - before).isLessThan(MAX_DIRECT_MEMORY_GROWTH);

            peer.resumeReading();
            Map<Integer, byte[]> digests = digestStreams(peer, visitors, each);
            for (Future<?> upload : uploads) {
                upload.get(30, TimeUnit.SECONDS);
            }
            for (int i = 0; i < visitors; i++) {
                assertThat(digests.get(i + 1)).as("stream %d", i + 1).isEqualTo(sha256(payloads.get(i)));
            }
        } finally {
            for (Socket socket : sockets) {
                socket.close();
            }
        }
    }

    private static TunnelHarness start(EdgeTransport transport, Duration pingInterval, Duration idleTimeout) {
        return new TunnelHarness(
                PortRange.parse(FIRST_PORT + "-" + (FIRST_PORT + 9)),
                new Random(),
                TunnelHarness.Settings.defaults().transport(transport).liveness(pingInterval, idleTimeout));
    }

    /** Moves a few MiB each way on stream 1 first, so the buffer pools are warm before memory is measured. */
    private void warmUp(ScriptedPeer peer, Socket visitor) throws Exception {
        byte[] bytes = randomBytes(4 * 1024 * 1024, 5);
        visitor.getOutputStream().write(bytes);
        assertThat(digestStreams(peer, 1, bytes.length).get(1)).isEqualTo(sha256(bytes));
        Future<?> download = threads.submit(() -> sendAll(peer, 1, bytes, new AtomicLong()));
        InputStream in = visitor.getInputStream();
        assertThat(sha256(in.readNBytes(bytes.length))).isEqualTo(sha256(bytes));
        download.get(30, TimeUnit.SECONDS);
    }

    /** Sends {@code bytes} on {@code streamId} within the server's credit, counting what has gone. */
    private static Void sendAll(ScriptedPeer peer, int streamId, byte[] bytes, AtomicLong sent) {
        for (int offset = 0; offset < bytes.length; offset += FrameLimits.MAX_DATA_PAYLOAD) {
            int length = Math.min(FrameLimits.MAX_DATA_PAYLOAD, bytes.length - offset);
            peer.sendData(streamId, Unpooled.wrappedBuffer(bytes, offset, length), SEND_TIMEOUT);
            sent.addAndGet(length);
        }
        return null;
    }

    /** Waits until {@code progress} has stopped moving for {@link #SETTLE}. */
    private static void awaitStalled(AtomicLong progress) throws InterruptedException {
        long deadline = System.nanoTime() + TIMEOUT.toNanos();
        long last = -1;
        while (progress.get() != last) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("Still moving after " + TIMEOUT + ": " + progress.get() + " bytes");
            }
            last = progress.get();
            TimeUnit.MILLISECONDS.sleep(SETTLE.toMillis());
        }
    }

    private static void writeInChunks(OutputStream out, byte[] bytes, AtomicLong written) throws Exception {
        for (int offset = 0; offset < bytes.length; offset += FrameLimits.MAX_DATA_PAYLOAD) {
            int length = Math.min(FrameLimits.MAX_DATA_PAYLOAD, bytes.length - offset);
            out.write(bytes, offset, length);
            written.addAndGet(length);
        }
    }

    /**
     * The SHA-256 of each stream's payload from {@code DATA} frames, releasing each, until {@code streams} streams
     * have each delivered {@code length} bytes. Keyed by stream id.
     */
    private static Map<Integer, byte[]> digestStreams(ScriptedPeer peer, int streams, int length) throws Exception {
        Map<Integer, MessageDigest> digests = new HashMap<>();
        Map<Integer, Long> received = new HashMap<>();
        long total = 0;
        while (total < (long) streams * length) {
            Frame.Data data = peer.expect(Frame.Data.class, TIMEOUT);
            try {
                ByteBuf payload = data.payload();
                int id = data.streamId();
                digests.computeIfAbsent(id, unused -> sha256Digest()).update(payload.nioBuffer());
                received.merge(id, (long) payload.readableBytes(), Long::sum);
                total += payload.readableBytes();
            } finally {
                data.release();
            }
        }
        assertThat(received)
                .hasSize(streams)
                .allSatisfy((id, bytes) -> assertThat(bytes).isEqualTo(length));
        Map<Integer, byte[]> result = new HashMap<>();
        digests.forEach((id, digest) -> result.put(id, digest.digest()));
        return result;
    }

    private static Socket visitor(RegisteredTunnel tunnel) throws Exception {
        Socket socket = new Socket(InetAddress.getLoopbackAddress(), tunnel.publicPort());
        socket.setSoTimeout((int) TIMEOUT.multipliedBy(3).toMillis());
        return socket;
    }

    static byte[] randomBytes(int length, long seed) {
        byte[] bytes = new byte[length];
        new Random(seed).nextBytes(bytes);
        return bytes;
    }

    static byte[] sha256(byte[] bytes) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(bytes);
    }

    private static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
