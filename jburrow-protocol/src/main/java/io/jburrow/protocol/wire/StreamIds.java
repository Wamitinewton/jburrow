package io.jburrow.protocol.wire;

/** Stream id rules: 0 is the control stream, the server allocates every other id in increasing order. */
public final class StreamIds {

    public static final int CONTROL = 0;
    public static final int MAX = 0x7FFF_FFFF;

    /** The top bit of the 32-bit stream id field. Reserved, and must be 0. */
    public static final int RESERVED_BIT = 0x8000_0000;

    private StreamIds() {}

    /** Whether the raw 32-bit stream id field read from the wire has the reserved bit set. */
    public static boolean isReservedBitSet(int raw) {
        return (raw & RESERVED_BIT) != 0;
    }

    /**
     * The id to allocate after {@code previous}. Pass {@link #CONTROL} for the first stream of a session.
     *
     * @throws ProtocolException with {@link ErrorCode#INTERNAL_ERROR} once every id up to {@link #MAX} is used
     */
    public static int next(int previous) {
        if (previous < CONTROL) {
            throw new IllegalArgumentException("Stream id must not be negative: " + previous);
        }
        if (previous == MAX) {
            throw ProtocolException.session(ErrorCode.INTERNAL_ERROR, "The session has used every stream id.");
        }
        return previous + 1;
    }
}
