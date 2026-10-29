package io.jburrow.protocol.wire;

import java.util.Arrays;
import java.util.Optional;

/**
 * The frame type registry. Codes {@code 0x00} and {@code 0x0C}–{@code 0xFF} are unassigned, and a released code is
 * never reused for another type.
 *
 * <p>{@link #maxPayload()} is the upper bound only. Exact lengths and minimums are in
 * {@link FrameLimits#lengthAllowed(FrameType, int, long)}.
 */
public enum FrameType {
    HELLO(0x01, StreamKind.CONTROL, 16_384),
    REGISTER(0x02, StreamKind.CONTROL, 16_384),
    OPEN(0x03, StreamKind.DATA, 16_384),
    HEADERS(0x04, StreamKind.DATA, 16_384),
    DATA(0x05, StreamKind.DATA, 65_536),
    WINDOW_UPDATE(0x06, StreamKind.ANY, 4),
    CLOSE(0x07, StreamKind.DATA, 64),
    PING(0x08, StreamKind.CONTROL, 8),
    PONG(0x09, StreamKind.CONTROL, 8),
    GOAWAY(0x0A, StreamKind.CONTROL, 16_384),
    ERROR(0x0B, StreamKind.CONTROL, 16_384);

    /** Which stream ids a frame type may be sent on. */
    public enum StreamKind {
        /** Stream 0 only. */
        CONTROL,
        /** Any stream other than 0. */
        DATA,
        /** Stream 0 or any other stream. */
        ANY
    }

    private static final int CODE_SPACE = 256;

    @SuppressWarnings("unchecked")
    private static final Optional<FrameType>[] BY_CODE = new Optional[CODE_SPACE];

    static {
        Arrays.fill(BY_CODE, Optional.empty());
        for (FrameType type : values()) {
            BY_CODE[type.code] = Optional.of(type);
        }
    }

    private final int code;
    private final StreamKind streamKind;
    private final int maxPayload;

    FrameType(int code, StreamKind streamKind, int maxPayload) {
        this.code = code;
        this.streamKind = streamKind;
        this.maxPayload = maxPayload;
    }

    public int code() {
        return code;
    }

    public StreamKind streamKind() {
        return streamKind;
    }

    /** Whether a frame of this type may be on {@code streamId}. */
    public boolean allowsStream(int streamId) {
        return switch (streamKind) {
            case CONTROL -> streamId == StreamIds.CONTROL;
            case DATA -> streamId != StreamIds.CONTROL;
            case ANY -> true;
        };
    }

    public int maxPayload() {
        return maxPayload;
    }

    /** The type for a wire code, or empty if the code is unassigned or outside {@code 0..255}. */
    public static Optional<FrameType> fromCode(int code) {
        if (code < 0 || code >= CODE_SPACE) {
            return Optional.empty();
        }
        return BY_CODE[code];
    }
}
