package io.jburrow.protocol.flow;

import io.jburrow.protocol.wire.ErrorCode;
import io.jburrow.protocol.wire.ProtocolException;
import java.util.Objects;

/**
 * What the peer may still send towards this side, on one stream or on the session, and the credit this side owes it
 * back. Every byte is in exactly one of three places, so {@code remaining + held + unreturned == size} always holds:
 *
 * <ul>
 *   <li>{@link #remaining()}: the peer's credit, as this side sees it;
 *   <li>{@link #held()}: received and not yet written to its destination or discarded;
 *   <li>{@link #unreturned()}: released, with its credit not yet sent back.
 * </ul>
 *
 * <p>Credit goes back once the unreturned bytes reach half the window (rounded down), never before a release. That
 * keeps updates rare and guarantees a sender with nothing held against it has more than half its window.
 *
 * <p>Read-only outside this package: only {@link SessionFlow} receives and releases.
 */
public final class ReceiveWindow {

    private final String scope;
    private final int size;
    private final int threshold;
    private int remaining;
    private int held;
    private int unreturned;
    private boolean returning = true;

    ReceiveWindow(String scope, int size) {
        this.scope = Objects.requireNonNull(scope, "scope");
        if (size < 1) {
            throw new IllegalArgumentException("A " + scope + " window is at least 1 byte, not " + size);
        }
        this.size = size;
        this.threshold = size / 2;
        this.remaining = size;
    }

    public int size() {
        return size;
    }

    public int remaining() {
        return remaining;
    }

    public int held() {
        return held;
    }

    public int unreturned() {
        return unreturned;
    }

    /** False once the peer has finished sending: released bytes are still counted, but no credit goes back. */
    public boolean isReturning() {
        return returning;
    }

    /**
     * @throws ProtocolException {@link ErrorCode#FLOW_CONTROL_ERROR} if the peer sent past the window
     */
    void checkReceive(int bytes) {
        if (bytes < 1) {
            throw new IllegalArgumentException("DATA carries at least 1 byte, not " + bytes);
        }
        if (bytes > remaining) {
            throw ProtocolException.session(
                    ErrorCode.FLOW_CONTROL_ERROR,
                    "DATA of " + bytes + " bytes overruns the " + scope + " window, which has " + remaining
                            + " bytes remaining");
        }
    }

    /** Counts bytes that arrived. Nothing changes if {@link #checkReceive} would throw. */
    void receive(int bytes) {
        checkReceive(bytes);
        remaining -= bytes;
        held += bytes;
    }

    /** Releasing what was never received would hand the peer credit for memory still in use: a bug in the caller. */
    void checkRelease(int bytes) {
        if (bytes < 1) {
            throw new IllegalArgumentException("A release is at least 1 byte, not " + bytes);
        }
        if (bytes > held) {
            throw new IllegalStateException(
                    "Releasing " + bytes + " bytes from the " + scope + " window, which holds " + held);
        }
    }

    /**
     * Counts bytes written to their destination or discarded, and returns the increment to send now, or 0 if none is
     * due. Nothing changes if {@link #checkRelease} would throw.
     */
    int release(int bytes) {
        checkRelease(bytes);
        held -= bytes;
        unreturned += bytes;
        if (!returning || unreturned < threshold) {
            return 0;
        }
        int increment = unreturned;
        unreturned = 0;
        remaining += increment;
        return increment;
    }

    void stopReturning() {
        returning = false;
    }

    @Override
    public String toString() {
        return "ReceiveWindow[" + scope + ", size=" + size + ", remaining=" + remaining + ", held=" + held
                + ", unreturned=" + unreturned + (returning ? "" : ", not returning") + "]";
    }
}
