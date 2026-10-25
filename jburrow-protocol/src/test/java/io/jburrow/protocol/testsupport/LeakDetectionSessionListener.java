package io.jburrow.protocol.testsupport;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.util.ResourceLeakDetector;
import io.netty.util.ResourceLeakDetectorFactory;
import org.junit.platform.launcher.LauncherSession;
import org.junit.platform.launcher.LauncherSessionListener;

/**
 * Installs {@link LeakRecordingFactory} at {@code PARANOID} before any test class loads, so it covers every buffer in
 * the test JVM. Registered through {@code META-INF/services}, so any module with the protocol test-jar on its test
 * classpath gets it.
 *
 * <p>Every buffer is tracked, but by default a leak's trace records only where the buffer was allocated, retained,
 * released and touched, not every read and write. A trace record is a stack capture, and WebSocket masking reads and
 * writes a buffer eight bytes at a time, which made the end-to-end suite spend minutes capturing stacks. Pass
 * {@code -Dio.netty.leakDetection.acquireAndReleaseOnly=false} to record every access when chasing a leak.
 */
public final class LeakDetectionSessionListener implements LauncherSessionListener {

    static final String ACQUIRE_AND_RELEASE_ONLY = "io.netty.leakDetection.acquireAndReleaseOnly";

    @Override
    public void launcherSessionOpened(LauncherSession session) {
        if (ResourceLeakDetectorFactory.instance() instanceof LeakRecordingFactory) {
            return;
        }
        if (System.getProperty(ACQUIRE_AND_RELEASE_ONLY) == null) {
            System.setProperty(ACQUIRE_AND_RELEASE_ONLY, "true");
        }
        ResourceLeakDetector.setLevel(ResourceLeakDetector.Level.PARANOID);
        ResourceLeakDetectorFactory.setResourceLeakDetectorFactory(new LeakRecordingFactory());
        initializeBufferLeakDetector();
    }

    /**
     * Loads Netty's buffer classes now, so their detector comes from the factory. If something loaded them first,
     * buffers would go unwatched and every leak check would pass; fail the run instead.
     */
    private static void initializeBufferLeakDetector() {
        ByteBuf probe = ByteBufAllocator.DEFAULT.buffer(1);
        probe.release();
        if (!LeakRecordingFactory.detects(ByteBuf.class)) {
            throw new IllegalStateException(
                    "Netty's ByteBuf leak detector was created before LeakDetectionSessionListener ran,"
                            + " so leaks can't be recorded");
        }
        LeakRecordingFactory.takeTrackedCount();
    }
}
