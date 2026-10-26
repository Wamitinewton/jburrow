package io.jburrow.protocol.flow;

import io.jburrow.protocol.wire.ErrorCode;
import io.jburrow.protocol.wire.ProtocolException;
import java.util.Objects;

/**
 * What this side may still send towards its peer, on one stream or on the session. It starts at the peer's announced
 * window, falls as {@code DATA} is sent and rises with the peer's {@code WINDOW_UPDATE}s.
 *
 * <p>Read-only outside this package: only {@link SessionFlow} spends and grants.
 */
public final class SendCredit {

    private final String scope;
    private long available;

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

    /**
     * Adds a {@code WINDOW_UPDATE}'s increment. The codec has already rejected an increment outside 1 to 2^31 − 1.
     *
     * @throws ProtocolException {@link ErrorCode#FLOW_CONTROL_ERROR} if the credit would pass 2^31 − 1; nothing changes
     */
    void grant(int increment) {
        if (increment < 1) {
            throw new IllegalArgumentException("A WINDOW_UPDATE increment is at least 1, not " + increment);
        }
        if (available + increment > FlowLimits.MAX_WINDOW) {
            throw ProtocolException.session(
                    ErrorCode.FLOW_CONTROL_ERROR,
                    "WINDOW_UPDATE of " + increment + " takes the " + scope + " credit of " + available + " past "
                            + FlowLimits.MAX_WINDOW);
        }
        available += increment;
    }

    @Override
    public String toString() {
        return "SendCredit[" + scope + ", available=" + available + "]";
    }
}
