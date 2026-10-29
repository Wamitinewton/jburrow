package io.jburrow.server.e2e;

import io.netty.util.ResourceLeakDetector;

/**
 * Lowers Netty's leak detection from {@code PARANOID}, which the test JVM runs at, to {@code SIMPLE} sampling until
 * closed, for a test that moves a million small frames. {@code PARANOID} captures a stack trace for every buffer it
 * tracks, and over that many frames the captures cost the event loops several times what the relay does: 500 streams of
 * 1 MiB each way take 24 s untracked and over a minute tracked.
 *
 * <p>A sampled detector still catches a leak that happens per frame or per stream, which is the kind such a test can
 * cause, and the class still fails on any it records. What it can't do is count what buffers hold:
 * {@link MemoryProbe}'s readings mean nothing while it is open, so a test that bounds memory never uses it.
 */
final class SampledLeakDetection implements AutoCloseable {

    private final ResourceLeakDetector.Level previous = ResourceLeakDetector.getLevel();

    SampledLeakDetection() {
        ResourceLeakDetector.setLevel(ResourceLeakDetector.Level.SIMPLE);
    }

    @Override
    public void close() {
        ResourceLeakDetector.setLevel(previous);
    }
}
