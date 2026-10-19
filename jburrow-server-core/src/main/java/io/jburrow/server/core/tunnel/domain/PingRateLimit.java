package io.jburrow.server.core.tunnel.domain;

import java.util.concurrent.TimeUnit;

/**
 * A token bucket for the client's {@code PING}s: a burst, then one per refill interval. Beyond it the session ends
 * with {@code PING_RATE_EXCEEDED}. Starts full, and a long idle period refills it only to the burst.
 *
 * <p>Kept as the time the bucket will next be full (the generic cell rate algorithm), so it holds two longs and no
 * timer. Times are {@code System.nanoTime()} readings and are only ever subtracted, so it holds across the
 * {@code long} wrap. Not thread-safe; confined to its session's event loop.
 */
public final class PingRateLimit {

    public static final int STANDARD_BURST = 5;
    public static final long STANDARD_REFILL_NANOS = TimeUnit.SECONDS.toNanos(1);

    private final long refillNanos;
    private final long burstToleranceNanos;
    private long fullAtNanos;

    public PingRateLimit(int burst, long refillNanos, long nowNanos) {
        if (burst < 1 || refillNanos < 1) {
            throw new IllegalArgumentException("A burst and a refill interval must be positive");
        }
        this.refillNanos = refillNanos;
        this.burstToleranceNanos = Math.multiplyExact(burst - 1L, refillNanos);
        this.fullAtNanos = nowNanos;
    }

    /** The reference server's limit: a burst of 5, then one per second. */
    public static PingRateLimit standard(long nowNanos) {
        return new PingRateLimit(STANDARD_BURST, STANDARD_REFILL_NANOS, nowNanos);
    }

    /** Takes one token if there is one. */
    public boolean tryAcquire(long nowNanos) {
        if (nowNanos - (fullAtNanos - burstToleranceNanos) < 0) {
            return false;
        }
        long from = nowNanos - fullAtNanos > 0 ? nowNanos : fullAtNanos;
        fullAtNanos = from + refillNanos;
        return true;
    }
}
