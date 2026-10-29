package io.jburrow.protocol.flow;

import io.jburrow.protocol.wire.ErrorCode;
import io.jburrow.protocol.wire.ProtocolException;
import java.util.Objects;

/**
 * What this side may still send towards its peer, on one stream or on the session. It starts at the peer's announced
 * window, falls as {@code DATA} is sent and rises with the peer's {@code WINDOW_UPDATE}s.
 *
 * <p>Credit can also be reserved: taken out of {@link #available()} ahead of a read, so nothing else can spend it, and
 * then either sent or refunded. The peer never learns of a reservation, so {@code available + reserved + sent} always
 * equals what the peer granted.
 *
 * <p>Read-only outside this package: only {@link SessionFlow} spends, reserves and grants.
 */
public final class SendCredit {

    private final String scope;
    private long available;
    private int reserved;

    SendCredit(String scope, int initial) {
        this.scope = Objects.requireNonNull(scope, "scope");
        if (initial < 1) {
            throw new IllegalArgumentException("A " + scope + " window is at least 1 byte, not " + initial);
        }
        this.available = initial;
    }

    /** At most {@link FlowLimits#MAX_WINDOW}. A {@code long} so the overflow check in a grant is an addition. */
    public long available() {
        return available;
    }

    /** Taken out of {@link #available()} and neither sent nor refunded yet. */
    public int reserved() {
        return reserved;
    }

    /**
     * Only this side spends, so spending more than is available is a bug in the caller, not something to report to
     * the peer.
     */
    void spend(int bytes) {
        if (bytes < 1) {
            throw new IllegalArgumentException("Sending takes at least 1 byte, not " + bytes);
        }
        if (bytes > available) {
            throw new IllegalStateException(
                    "Sending " + bytes + " bytes exceeds the " + scope + " credit of " + available);
        }
        available -= bytes;
    }

    /** Moves {@code bytes} from available to reserved. Like {@link #spend}, taking more than is available is a bug. */
    void reserve(int bytes) {
        if (bytes < 1) {
            throw new IllegalArgumentException("A reservation is at least 1 byte, not " + bytes);
        }
        if (bytes > available) {
            throw new IllegalStateException(
                    "Reserving " + bytes + " bytes exceeds the " + scope + " credit of " + available);
        }
        available -= bytes;
        reserved += bytes;
    }

    /** Sends {@code bytes} out of the reservation, so nothing is taken from {@link #available()}. */
    void spendReserved(int bytes) {
        checkReserved("Sending", bytes);
        reserved -= bytes;
    }

    /** Puts {@code bytes} of the reservation back into {@link #available()}. */
    void refund(int bytes) {
        checkReserved("Refunding", bytes);
        reserved -= bytes;
        available += bytes;
    }

    void checkReserved(String action, int bytes) {
        if (bytes < 1) {
            throw new IllegalArgumentException(action + " reserved credit takes at least 1 byte, not " + bytes);
        }
        if (bytes > reserved) {
            throw new IllegalStateException(
                    action + " " + bytes + " reserved bytes exceeds the " + scope + " reservation of " + reserved);
        }
    }

    /**
     * Adds a {@code WINDOW_UPDATE}'s increment. The codec has already rejected an increment outside 1 to 2^31 − 1.
     *
     * <p>The peer's view of this credit includes what is reserved, so the limit does too: a refund can never take the
     * credit past it.
     *
     * @throws ProtocolException {@link ErrorCode#FLOW_CONTROL_ERROR} if the credit would pass 2^31 − 1; nothing changes
     */
    void grant(int increment) {
        if (increment < 1) {
            throw new IllegalArgumentException("A WINDOW_UPDATE increment is at least 1, not " + increment);
        }
        long credit = available + reserved;
        if (credit + increment > FlowLimits.MAX_WINDOW) {
            throw ProtocolException.session(
                    ErrorCode.FLOW_CONTROL_ERROR,
                    "WINDOW_UPDATE of " + increment + " takes the " + scope + " credit of " + credit + " past "
                            + FlowLimits.MAX_WINDOW);
        }
        available += increment;
    }

    @Override
    public String toString() {
        return "SendCredit[" + scope + ", available=" + available + (reserved == 0 ? "" : ", reserved=" + reserved)
                + "]";
    }
}
