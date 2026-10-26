package io.jburrow.protocol.flow;

/** The bounds on announced windows and on credit. */
public final class FlowLimits {

    /** The smallest stream window a side may announce: one full {@code DATA} payload. */
    public static final int MIN_STREAM_WINDOW = 65_536;

    /** The largest window, and the most credit a sender may ever hold: 2^31 − 1. */
    public static final int MAX_WINDOW = Integer.MAX_VALUE;

    private FlowLimits() {}
}
