package io.jburrow.server.core.tunnel.application;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * The server-wide cap on open streams, across every session. Each stream costs a visitor connection's descriptor,
 * channel and kernel socket memory, which the per-session limit alone doesn't bound once there are many sessions.
 *
 * <p>Thread-safe: shared by every event loop, and touched only when a visitor is admitted and when its stream ends,
 * never on the byte path. Each successful {@link #tryAcquire} is matched by exactly one {@link #release}.
 */
public final class StreamQuota {

    private final int max;
    private final AtomicInteger inUse = new AtomicInteger();

    public StreamQuota(int max) {
        if (max < 1) {
            throw new IllegalArgumentException("The stream quota must be positive: " + max);
        }
        this.max = max;
    }

    /** Takes one stream, or returns false if the server is at its cap. */
    public boolean tryAcquire() {
        while (true) {
            int current = inUse.get();
            if (current >= max) {
                return false;
            }
            if (inUse.compareAndSet(current, current + 1)) {
                return true;
            }
        }
    }

    /**
     * Gives one stream back.
     *
     * @throws IllegalStateException if nothing is held: a release without its acquire
     */
    public void release() {
        while (true) {
            int current = inUse.get();
            if (current < 1) {
                throw new IllegalStateException("The stream quota was released more often than it was acquired");
            }
            if (inUse.compareAndSet(current, current - 1)) {
                return;
            }
        }
    }

    public int inUse() {
        return inUse.get();
    }

    public int max() {
        return max;
    }
}
