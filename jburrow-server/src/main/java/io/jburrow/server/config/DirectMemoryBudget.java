package io.jburrow.server.config;

import io.jburrow.protocol.wire.FrameLimits;
import java.util.Locale;

/**
 * The most direct memory the edge's settings let clients make the server hold, set against the direct memory limit
 * ({@code docs/phase-2-multiplexing/ARCHITECTURE.md} §Memory budget). The worst case needs every session saturated at
 * once, so exceeding the limit is a warning, not a startup failure.
 *
 * @param perSession bytes one established session can make the server hold
 * @param sessions sessions the server admits
 * @param handshakes connections that may be handshaking at once
 * @param limit the direct memory limit, in bytes
 */
record DirectMemoryBudget(long perSession, int sessions, int handshakes, long limit) {

    /** Above this share of the limit the budget is logged as a warning. */
    static final double WARN_SHARE = 0.9;

    /** One frame: an overrunning message, the overshoot past a high watermark, or a handshaking connection's message. */
    private static final long FRAME = FrameLimits.MAX_DATA_PAYLOAD;

    private static final long KIB = 1024;
    private static final long MIB = 1024 * KIB;

    DirectMemoryBudget {
        if (perSession < 0 || sessions < 0 || handshakes < 0) {
            throw new IllegalArgumentException("A budget can't be negative");
        }
        if (limit < 1) {
            throw new IllegalArgumentException("The direct memory limit must be positive: " + limit);
        }
    }

    /**
     * Per session: the session window (client {@code DATA} not yet written to visitors), one message that overruns it
     * and is held until it is rejected, and the session channel's high watermark plus the one frame a capped read can
     * pass it by. Carried bytes are on the heap.
     */
    static DirectMemoryBudget of(JBurrowProperties.Edge edge, long maxDirectMemory) {
        long perSession = edge.sessionWindow().toBytes()
                + FRAME
                + edge.sessionWatermarks().high()
                + FRAME;
        return new DirectMemoryBudget(perSession, edge.maxSessions(), edge.maxHandshaking(), maxDirectMemory);
    }

    long total() {
        return perSession * sessions + handshakes * FRAME;
    }

    /** {@link #total()} as a fraction of {@link #limit()}. */
    double share() {
        return (double) total() / limit;
    }

    boolean exceedsWarnShare() {
        return share() > WARN_SHARE;
    }

    /** For example {@code 200 sessions × 896 KiB + 64 handshakes × 64 KiB = 179 MiB of 256 MiB (70%)}. */
    String describe() {
        return String.format(
                Locale.ROOT,
                "%d sessions × %d KiB + %d handshakes × %d KiB = %d MiB of %d MiB (%d%%)",
                sessions,
                Math.round((double) perSession / KIB),
                handshakes,
                FRAME / KIB,
                Math.round((double) total() / MIB),
                Math.round((double) limit / MIB),
                Math.round(share() * 100));
    }
}
