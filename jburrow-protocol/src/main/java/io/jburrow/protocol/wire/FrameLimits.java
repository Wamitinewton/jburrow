package io.jburrow.protocol.wire;

/** Sizes fixed by the protocol, and the payload length each frame type allows. */
public final class FrameLimits {

    public static final int HEADER_LENGTH = 10;
    public static final int MAX_DATA_PAYLOAD = 65_536;
    public static final int MAX_CONTROL_PAYLOAD = 16_384;
    public static final int MAX_MESSAGE_LENGTH = HEADER_LENGTH + MAX_DATA_PAYLOAD;
    public static final int PING_PAYLOAD_LENGTH = 8;
    public static final int WINDOW_UPDATE_PAYLOAD_LENGTH = 4;
    public static final int MAX_ERROR_CODE_LENGTH = 64;

    private FrameLimits() {}

    /**
     * Whether {@code length} is a valid payload length for {@code type}. For {@link FrameType#CLOSE} it depends on the
     * {@link FrameFlags#RST} bit of {@code flags}; other flag bits are ignored here and checked separately.
     */
    public static boolean lengthAllowed(FrameType type, int flags, long length) {
        return switch (type) {
            case HELLO, REGISTER, OPEN, HEADERS, GOAWAY, ERROR -> between(length, 0, MAX_CONTROL_PAYLOAD);
            case DATA -> between(length, 1, MAX_DATA_PAYLOAD);
            case WINDOW_UPDATE -> length == WINDOW_UPDATE_PAYLOAD_LENGTH;
            case PING, PONG -> length == PING_PAYLOAD_LENGTH;
            case CLOSE -> (flags & FrameFlags.RST) == 0 ? length == 0 : between(length, 1, MAX_ERROR_CODE_LENGTH);
        };
    }

    private static boolean between(long value, long min, long max) {
        return value >= min && value <= max;
    }
}
