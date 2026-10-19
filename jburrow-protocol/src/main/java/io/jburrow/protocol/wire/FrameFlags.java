package io.jburrow.protocol.wire;

/** Flag bits per frame type. A bit a type doesn't define must be 0 on the wire. */
public final class FrameFlags {

    /** On {@link FrameType#CLOSE}: reset the stream in both directions. */
    public static final int RST = 0x01;

    private static final int NONE = 0x00;

    private FrameFlags() {}

    /** The mask of flag bits defined for {@code type}. */
    public static int definedFor(FrameType type) {
        return type == FrameType.CLOSE ? RST : NONE;
    }
}
