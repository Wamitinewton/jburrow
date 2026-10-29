package io.jburrow.server.e2e;

import io.jburrow.protocol.testsupport.LeakRecordingFactory;
import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The memory readings of the slow-client tests. Both cover the whole JVM, the client included, so a bound on them
 * allows for both ends of the tunnel.
 *
 * <ul>
 *   <li><em>Direct bytes held</em>: the leak detector's count of direct memory live buffers hold, at the capacity each
 *       was allocated with. Unlike an allocator's own figures it doesn't count chunks kept for reuse, so it moves with
 *       what the byte path holds.
 *   <li><em>Heap after GC</em>: the heap in use after three collections.
 * </ul>
 *
 * <p>An instance samples direct bytes held from its start until closed, keeping the most it saw above its first reading.
 */
final class MemoryProbe implements AutoCloseable {

    private static final int COLLECTIONS = 3;
    private static final Duration BETWEEN_COLLECTIONS = Duration.ofMillis(100);

    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();
    private final long baseline = directBytesHeld();
    private final AtomicLong mostHeld = new AtomicLong(baseline);

    private MemoryProbe(Duration interval) {
        timer.scheduleAtFixedRate(
                () -> mostHeld.accumulateAndGet(directBytesHeld(), Math::max),
                0,
                interval.toMillis(),
                TimeUnit.MILLISECONDS);
    }

    /** Samples direct bytes held every {@code interval} from now. */
    static MemoryProbe sampleEvery(Duration interval) {
        return new MemoryProbe(interval);
    }

    static long directBytesHeld() {
        return LeakRecordingFactory.heldDirectBytes();
    }

    static long heapAfterGc() throws InterruptedException {
        for (int i = 0; i < COLLECTIONS; i++) {
            System.gc();
            Thread.sleep(BETWEEN_COLLECTIONS);
        }
        return ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
    }

    /** The most direct bytes held seen so far, above the reading when sampling started. */
    long mostDirectGrowth() {
        mostHeld.accumulateAndGet(directBytesHeld(), Math::max);
        return mostHeld.get() - baseline;
    }

    @Override
    public void close() {
        timer.shutdownNow();
    }
}
