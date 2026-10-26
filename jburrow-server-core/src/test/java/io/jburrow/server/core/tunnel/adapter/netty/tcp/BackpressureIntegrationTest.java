package io.jburrow.server.core.tunnel.adapter.netty.tcp;

import static io.jburrow.server.core.tunnel.adapter.netty.tcp.TunnelHarness.TIMEOUT;
import static org.assertj.core.api.Assertions.assertThat;

import io.jburrow.protocol.adapter.netty.Frame;
import io.jburrow.protocol.control.RegisteredTunnel;
import io.jburrow.protocol.testsupport.ScriptedPeer;
import io.jburrow.protocol.wire.FrameLimits;
import io.jburrow.server.core.portpool.domain.PortRange;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * {@code docs/phase-1-tcp-tunnel/ARCHITECTURE.md} §Backpressure in phase 1 over real sockets: whichever side stops
 * reading, the server stops reading the other, so its memory stays flat, and nothing is lost when reading resumes.
 */
class BackpressureIntegrationTest {

    private static final int FIRST_PORT = 41_200;
    private static final int TRANSFER = 64 * 1024 * 1024;
    private static final int CHUNK = 64 * 1024;
    private static final long MAX_DIRECT_MEMORY_GROWTH = 2L * 1024 * 1024;
    private static final Duration SETTLE = Duration.ofMillis(500);

    private final ExecutorService threads = Executors.newVirtualThreadPerTaskExecutor();
    private TunnelHarness harness;

    @AfterEach
    void stop() throws Exception {
        if (harness != null) {
            harness.close();
        }
        threads.shutdownNow();
    }

    @Test
    void aClientThatStopsReadingStopsTheServerReadingTheVisitor() throws Exception {
        harness = start(Duration.ofSeconds(15), Duration.ofSeconds(45));
        ScriptedPeer peer = harness.connect();
        RegisteredTunnel tunnel = harness.establish(peer, null);
        byte[] payload = randomBytes(TRANSFER, 3);

        try (Socket visitor = visitor(tunnel)) {
            peer.expect(Frame.Open.class, TIMEOUT);
            warmUp(peer, visitor);
            LongSupplier directMemory = harness.edge::usedDirectMemory;
            long before = directMemory.getAsLong();
            assertThat(before).as("direct memory is measured").isNotNegative();
            peer.pauseReading();

            AtomicLong written = new AtomicLong();
            Future<?> upload = threads.submit(() -> {
                writeInChunks(visitor.getOutputStream(), payload, written);
                return null;
            });
            awaitStalled(written);
            long stalledAt = written.get();
            assertThat(stalledAt).isLessThan(TRANSFER);
            assertThat(directMemory.getAsLong() - before).isLessThan(MAX_DIRECT_MEMORY_GROWTH);
            TimeUnit.MILLISECONDS.sleep(SETTLE.toMillis());
            assertThat(written.get()).isEqualTo(stalledAt);
            assertThat(directMemory.getAsLong() - before).isLessThan(MAX_DIRECT_MEMORY_GROWTH);
            assertThat(harness.metrics.events()).contains("backpressurePaused INBOUND");

            peer.resumeReading();
            byte[] digest = digestData(peer, TRANSFER);
            upload.get(30, TimeUnit.SECONDS);
            assertThat(digest).isEqualTo(sha256(payload));
        }
    }

    @Test
    void aVisitorThatStopsReadingStopsTheServerReadingTheClientWithoutAHeartbeatTimeout() throws Exception {
        Duration idleTimeout = Duration.ofSeconds(2);
        harness = start(Duration.ofSeconds(1), idleTimeout);
        ScriptedPeer peer = harness.connect();
        RegisteredTunnel tunnel = harness.establish(peer, null);
        byte[] payload = randomBytes(TRANSFER, 4);

        try (Socket visitor = visitor(tunnel)) {
            peer.expect(Frame.Open.class, TIMEOUT);
            AtomicLong sent = new AtomicLong();
            Future<?> download = threads.submit(() -> {
                for (int offset = 0; offset < payload.length; offset += FrameLimits.MAX_DATA_PAYLOAD) {
                    int length = Math.min(FrameLimits.MAX_DATA_PAYLOAD, payload.length - offset);
                    peer.send(new Frame.Data(1, Unpooled.wrappedBuffer(payload, offset, length)));
                    sent.addAndGet(length);
                }
                return null;
            });

            awaitStalled(sent);
            long stalledAt = sent.get();
            assertThat(stalledAt).isLessThan(TRANSFER);
            TimeUnit.MILLISECONDS.sleep(idleTimeout.multipliedBy(3).toMillis());
            assertThat(sent.get()).isEqualTo(stalledAt);
            assertThat(harness.metrics.events())
                    .contains("backpressurePaused OUTBOUND")
                    .doesNotContain("sessionEnded HEARTBEAT_TIMEOUT");
            assertThat(harness.sessions.registry().active()).isOne();

            byte[] received = readFully(visitor, TRANSFER);
            download.get(30, TimeUnit.SECONDS);
            assertThat(received).hasSize(TRANSFER);
            assertThat(sha256(received)).isEqualTo(sha256(payload));
        }
    }

    /**
     * Reads {@code length} bytes from the visitor. A reset alone doesn't say why the server ended the stream, so a failed
     * read reports what the server recorded, leaving out the per-frame and byte-count events.
     */
    private byte[] readFully(Socket visitor, int length) throws IOException {
        try {
            return visitor.getInputStream().readNBytes(length);
        } catch (IOException e) {
            List<String> recorded = harness.metrics.events().stream()
                    .filter(event -> !event.startsWith("frame ") && !event.startsWith("tunnelBytes "))
                    .toList();
            throw new AssertionError("The visitor's connection failed; the server recorded " + recorded, e);
        }
    }

    private static TunnelHarness start(Duration pingInterval, Duration idleTimeout) {
        return new TunnelHarness(
                PortRange.parse(FIRST_PORT + "-" + (FIRST_PORT + 9)), new Random(), pingInterval, idleTimeout);
    }

    /** Moves a few MiB each way first, so the buffer pools are warm before memory is measured. */
    private static void warmUp(ScriptedPeer peer, Socket visitor) throws Exception {
        byte[] bytes = randomBytes(4 * 1024 * 1024, 5);
        visitor.getOutputStream().write(bytes);
        assertThat(digestData(peer, bytes.length)).isEqualTo(sha256(bytes));
        for (int offset = 0; offset < bytes.length; offset += FrameLimits.MAX_DATA_PAYLOAD) {
            peer.send(new Frame.Data(1, Unpooled.wrappedBuffer(bytes, offset, FrameLimits.MAX_DATA_PAYLOAD)));
        }
        InputStream in = visitor.getInputStream();
        assertThat(sha256(in.readNBytes(bytes.length))).isEqualTo(sha256(bytes));
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
        for (int offset = 0; offset < bytes.length; offset += CHUNK) {
            int length = Math.min(CHUNK, bytes.length - offset);
            out.write(bytes, offset, length);
            written.addAndGet(length);
        }
    }

    /** The SHA-256 of {@code length} payload bytes taken from {@code DATA} frames, releasing each. */
    private static byte[] digestData(ScriptedPeer peer, int length) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        long received = 0;
        while (received < length) {
            Frame.Data data = peer.expect(Frame.Data.class, TIMEOUT);
            try {
                ByteBuf payload = data.payload();
                received += payload.readableBytes();
                digest.update(payload.nioBuffer());
            } finally {
                data.release();
            }
        }
        assertThat(received).isEqualTo(length);
        return digest.digest();
    }

    private static Socket visitor(RegisteredTunnel tunnel) throws Exception {
        Socket socket = new Socket(InetAddress.getLoopbackAddress(), tunnel.publicPort());
        socket.setSoTimeout((int) TIMEOUT.multipliedBy(3).toMillis());
        return socket;
    }

    private static byte[] randomBytes(int length, long seed) {
        byte[] bytes = new byte[length];
        new Random(seed).nextBytes(bytes);
        return bytes;
    }

    private static byte[] sha256(byte[] bytes) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(bytes);
    }
}
