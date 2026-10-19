package io.jburrow.protocol.liveness;

/**
 * Decides when to send a {@code PING} and when the peer is dead, from the time since anything was last received.
 * Every received frame is proof of life, not only a {@code PONG}, and at most one {@code PING} is outstanding.
 *
 * <p>While reading is paused for backpressure the clock stops: nothing can arrive, so silence proves nothing. On
 * resume it restarts from zero.
 *
 * <p>Times are {@code System.nanoTime()} readings passed in by the caller and are only ever subtracted, so the rules
 * hold across the {@code long} wrap. Not thread-safe; confined to the thread that owns its connection.
 */
public final class Liveness {

    /** {@link #nextDeadlineNanos} while paused: nothing is due until {@link #resume}. */
    public static final long NO_DEADLINE = Long.MAX_VALUE;

    private long pingIntervalNanos;
    private long idleTimeoutNanos;
    private long lastReceivedNanos;
    private boolean pingOutstanding;
    private boolean pongAwaited;
    private long awaitedOpaque;
    private boolean paused;

    /**
     * @param pingIntervalNanos silence after which a {@code PING} is sent; positive and at most the idle timeout
     * @param idleTimeoutNanos silence after which the peer is dead; positive
     * @param nowNanos the start of the clock, as if a frame had just been received
     */
    public Liveness(long pingIntervalNanos, long idleTimeoutNanos, long nowNanos) {
        setIntervals(pingIntervalNanos, idleTimeoutNanos);
        this.lastReceivedNanos = nowNanos;
    }

    public void onFrameReceived(long nowNanos) {
        lastReceivedNanos = nowNanos;
        pingOutstanding = false;
    }

    /**
     * Records the {@code PING} the caller sent after {@link #check} returned {@link LivenessAction#SEND_PING}.
     *
     * @throws IllegalStateException if no {@code PING} was due at {@code nowNanos}
     */
    public void onPingSent(long nowNanos, long opaque) {
        if (check(nowNanos) != LivenessAction.SEND_PING) {
            throw new IllegalStateException("No PING is due");
        }
        pingOutstanding = true;
        pongAwaited = true;
        awaitedOpaque = opaque;
    }

    /**
     * Whether a {@code PONG} echoes the last {@code PING} sent and is its first answer. A {@code PONG} that doesn't is a
     * stray, to be discarded. The {@code PONG} counts as proof of life through {@link #onFrameReceived}, not here.
     */
    public boolean onPongReceived(long opaque) {
        if (!pongAwaited || opaque != awaitedOpaque) {
            return false;
        }
        pongAwaited = false;
        return true;
    }

    /** Stops the clock because the caller stopped reading the connection. Pausing while paused does nothing. */
    public void pause() {
        paused = true;
    }

    /** Restarts the clock from zero because reading resumed. Resuming while not paused does nothing. */
    public void resume(long nowNanos) {
        if (!paused) {
            return;
        }
        paused = false;
        lastReceivedNanos = nowNanos;
        pingOutstanding = false;
    }

    /**
     * Replaces both intervals and restarts the clock from {@code nowNanos}. The client calls it when the server's
     * {@code HELLO} announces them.
     */
    public void reconfigure(long pingIntervalNanos, long idleTimeoutNanos, long nowNanos) {
        setIntervals(pingIntervalNanos, idleTimeoutNanos);
        lastReceivedNanos = nowNanos;
        pingOutstanding = false;
    }

    public LivenessAction check(long nowNanos) {
        if (paused) {
            return LivenessAction.NONE;
        }
        long silent = nowNanos - lastReceivedNanos;
        if (silent >= idleTimeoutNanos) {
            return LivenessAction.DEAD;
        }
        if (silent >= pingIntervalNanos && !pingOutstanding) {
            return LivenessAction.SEND_PING;
        }
        return LivenessAction.NONE;
    }

    /**
     * The earliest moment {@link #check} can return something other than {@link LivenessAction#NONE}, so the caller
     * keeps exactly one timer. Returns {@code nowNanos} when an action is already due, and {@link #NO_DEADLINE} while
     * paused.
     */
    public long nextDeadlineNanos(long nowNanos) {
        if (paused) {
            return NO_DEADLINE;
        }
        long deadline = lastReceivedNanos + (pingOutstanding ? idleTimeoutNanos : pingIntervalNanos);
        return deadline - nowNanos <= 0 ? nowNanos : deadline;
    }

    private void setIntervals(long pingIntervalNanos, long idleTimeoutNanos) {
        if (pingIntervalNanos <= 0 || idleTimeoutNanos <= 0) {
            throw new IllegalArgumentException("Liveness intervals must be positive");
        }
        if (pingIntervalNanos > idleTimeoutNanos) {
            throw new IllegalArgumentException("The ping interval must not exceed the idle timeout");
        }
        this.pingIntervalNanos = pingIntervalNanos;
        this.idleTimeoutNanos = idleTimeoutNanos;
    }
}
