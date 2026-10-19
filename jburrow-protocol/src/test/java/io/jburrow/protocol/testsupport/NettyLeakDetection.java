package io.jburrow.protocol.testsupport;

import io.netty.buffer.ByteBufAllocator;
import io.netty.util.ResourceLeakDetectorFactory;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * Fails a test class if any Netty buffer leaked while it ran. Registered for every test class through JUnit's
 * extension auto-detection, which the test-jar's {@code junit-platform.properties} switches on.
 *
 * <p>Netty only notices a leak once the garbage collector has cleared the buffer, and only reports it on a later
 * allocation, so the check collects garbage and allocates before it looks. It is a safety net: a leak can surface one
 * test class late, and a test that owns its buffers should also assert {@code refCnt() == 0} itself.
 */
public final class NettyLeakDetection implements AfterAllCallback {

    private static final int ROUNDS = 2;
    private static final long PAUSE_MILLIS = 20;
    private static final int PROBES = 4;

    @Override
    public void afterAll(ExtensionContext context) throws InterruptedException {
        if (!(ResourceLeakDetectorFactory.instance() instanceof LeakRecordingFactory)) {
            throw new AssertionError("Netty leak detection isn't installed: LeakDetectionSessionListener didn't run");
        }
        if (LeakRecordingFactory.takeTrackedCount() > 0) {
            provokeLeakReports();
        }
        List<String> leaks = LeakRecordingFactory.drainLeaks();
        if (!leaks.isEmpty()) {
            throw new AssertionError(leaks.size() + " Netty buffer leak(s) in or before " + context.getDisplayName()
                    + ":\n\n" + String.join("\n\n", leaks));
        }
    }

    /** Collects garbage, then allocates, so Netty reports any buffer collected without being released. */
    static void provokeLeakReports() throws InterruptedException {
        for (int round = 0; round < ROUNDS; round++) {
            System.gc();
            TimeUnit.MILLISECONDS.sleep(PAUSE_MILLIS);
            for (int i = 0; i < PROBES; i++) {
                ByteBufAllocator.DEFAULT.buffer(1).release();
            }
        }
        LeakRecordingFactory.takeTrackedCount();
    }
}
