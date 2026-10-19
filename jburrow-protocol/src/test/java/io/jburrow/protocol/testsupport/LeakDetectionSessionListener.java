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
 */
public final class LeakDetectionSessionListener implements LauncherSessionListener {

    @Override
    public void launcherSessionOpened(LauncherSession session) {
        if (ResourceLeakDetectorFactory.instance() instanceof LeakRecordingFactory) {
            return;
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
