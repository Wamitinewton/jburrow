package io.jburrow.protocol.testsupport;

import io.netty.util.ResourceLeakDetector;
import io.netty.util.ResourceLeakDetectorFactory;
import io.netty.util.ResourceLeakTracker;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Creates leak detectors that record every leak report as well as logging it, so a test can fail on one.
 *
 * <p>Netty's {@code LeakListener} is set per detector instance, and the {@code ByteBuf} detector is created once, when
 * the buffer classes initialize. Installing this factory before then, which {@link LeakDetectionSessionListener}
 * does, is the only way to see every report.
 */
public final class LeakRecordingFactory extends ResourceLeakDetectorFactory {

    private static final Queue<String> LEAKS = new ConcurrentLinkedQueue<>();
    private static final Queue<String> DETECTED_TYPES = new ConcurrentLinkedQueue<>();
    private static final AtomicLong TRACKED = new AtomicLong();

    @Override
    public <T> ResourceLeakDetector<T> newResourceLeakDetector(
            Class<T> resource, int samplingInterval, long maxActive) {
        DETECTED_TYPES.add(resource.getName());
        return new RecordingDetector<>(resource, samplingInterval);
    }

    /** Every leak recorded since the last call, which clears them. */
    public static List<String> drainLeaks() {
        List<String> leaks = new ArrayList<>();
        for (String leak = LEAKS.poll(); leak != null; leak = LEAKS.poll()) {
            leaks.add(leak);
        }
        return leaks;
    }

    /** Whether this factory created the detector for {@code resource}. */
    public static boolean detects(Class<?> resource) {
        return DETECTED_TYPES.contains(resource.getName());
    }

    /** How many objects were tracked since the last call, which resets the count. */
    static long takeTrackedCount() {
        return TRACKED.getAndSet(0);
    }

    private static final class RecordingDetector<T> extends ResourceLeakDetector<T> {

        RecordingDetector(Class<T> resource, int samplingInterval) {
            super(resource, samplingInterval);
        }

        @Override
        public ResourceLeakTracker<T> track(T obj) {
            ResourceLeakTracker<T> tracker = super.track(obj);
            if (tracker != null) {
                TRACKED.incrementAndGet();
            }
            return tracker;
        }

        /** Netty skips reporting when its logger has errors off; recording mustn't depend on logging set-up. */
        @Override
        protected boolean needReport() {
            return true;
        }

        @Override
        protected void reportTracedLeak(String resourceType, String records) {
            LEAKS.add(resourceType + " leaked." + records);
            super.reportTracedLeak(resourceType, records);
        }

        @Override
        protected void reportUntracedLeak(String resourceType) {
            LEAKS.add(resourceType + " leaked, with no access records.");
            super.reportUntracedLeak(resourceType);
        }
    }
}
