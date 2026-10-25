package io.jburrow.protocol.testsupport;

import static org.assertj.core.api.Assertions.assertThat;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.util.ResourceLeakDetector;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Proves the leak detection every byte-path test relies on actually fires. */
class LeakDetectionTest {

    private static final int MAX_ATTEMPTS = 50;

    @Test
    void runsAtParanoidWithTheRecordingFactory() {
        assertThat(ResourceLeakDetector.getLevel()).isEqualTo(ResourceLeakDetector.Level.PARANOID);
        assertThat(LeakRecordingFactory.detects(ByteBuf.class)).isTrue();
        assertThat(System.getProperty(LeakDetectionSessionListener.ACQUIRE_AND_RELEASE_ONLY))
                .isEqualTo("true");
    }

    /**
     * Recording only acquire and release still names where a leaked buffer was allocated. The leak is cleared once
     * seen, so the class-level check doesn't fail this class for it.
     */
    @Test
    void recordsABufferThatWasNeverReleased() throws InterruptedException {
        leakOneBuffer();

        List<String> leaks = awaitLeaks();

        assertThat(leaks).anySatisfy(leak -> assertThat(leak).contains("leakOneBuffer"));
    }

    @Test
    void countsTheDirectBytesALiveBufferHoldsUntilItsRelease() {
        long before = LeakRecordingFactory.heldDirectBytes();

        ByteBuf direct = ByteBufAllocator.DEFAULT.directBuffer(4096);
        ByteBuf slice = direct.retainedSlice(0, 16);
        assertThat(LeakRecordingFactory.heldDirectBytes() - before).isEqualTo(4096);

        direct.release();
        assertThat(LeakRecordingFactory.heldDirectBytes() - before).isEqualTo(4096);
        slice.release();
        assertThat(LeakRecordingFactory.heldDirectBytes()).isEqualTo(before);

        ByteBufAllocator.DEFAULT.heapBuffer(4096).release();
        assertThat(LeakRecordingFactory.heldDirectBytes()).isEqualTo(before);
    }

    private static void leakOneBuffer() {
        ByteBufAllocator.DEFAULT.buffer(16).writeLong(1);
    }

    private static List<String> awaitLeaks() throws InterruptedException {
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            NettyLeakDetection.provokeLeakReports();
            List<String> leaks = LeakRecordingFactory.drainLeaks();
            if (!leaks.isEmpty()) {
                return leaks;
            }
        }
        return List.of();
    }
}
