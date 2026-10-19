package io.jburrow.protocol.wire;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import io.jburrow.protocol.wire.FrameType.StreamKind;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class FrameTypeTest {

    /** {@code docs/protocol.md} §6, row for row. */
    static Stream<Arguments> registry() {
        return Stream.of(
                arguments(FrameType.HELLO, 0x01, StreamKind.CONTROL, 16_384),
                arguments(FrameType.REGISTER, 0x02, StreamKind.CONTROL, 16_384),
                arguments(FrameType.OPEN, 0x03, StreamKind.DATA, 16_384),
                arguments(FrameType.HEADERS, 0x04, StreamKind.DATA, 16_384),
                arguments(FrameType.DATA, 0x05, StreamKind.DATA, 65_536),
                arguments(FrameType.WINDOW_UPDATE, 0x06, StreamKind.DATA, 4),
                arguments(FrameType.CLOSE, 0x07, StreamKind.DATA, 64),
                arguments(FrameType.PING, 0x08, StreamKind.CONTROL, 8),
                arguments(FrameType.PONG, 0x09, StreamKind.CONTROL, 8),
                arguments(FrameType.GOAWAY, 0x0A, StreamKind.CONTROL, 16_384),
                arguments(FrameType.ERROR, 0x0B, StreamKind.CONTROL, 16_384));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("registry")
    void matchesTheArchitectureTable(FrameType type, int code, StreamKind streamKind, int maxPayload) {
        assertThat(type.code()).isEqualTo(code);
        assertThat(type.streamKind()).isEqualTo(streamKind);
        assertThat(type.onControlStream()).isEqualTo(streamKind == StreamKind.CONTROL);
        assertThat(type.maxPayload()).isEqualTo(maxPayload);
        assertThat(FrameType.fromCode(code)).contains(type);
    }

    @Test
    void registryHasNoOtherTypes() {
        assertThat(registry()).hasSameSizeAs(FrameType.values());
    }

    @Test
    void unassignedCodesAreEmpty() {
        assertThat(FrameType.fromCode(0x00)).isEmpty();
        IntStream.rangeClosed(0x0C, 0xFF)
                .forEach(code -> assertThat(FrameType.fromCode(code))
                        .as("code 0x%02X", code)
                        .isEmpty());
    }

    @Test
    void codesOutsideOneByteAreEmpty() {
        assertThat(FrameType.fromCode(-1)).isEmpty();
        assertThat(FrameType.fromCode(0x100)).isEmpty();
        assertThat(FrameType.fromCode(0x101)).isEmpty();
        assertThat(FrameType.fromCode(Integer.MIN_VALUE)).isEmpty();
    }

    @Test
    void noTypeAllowsMoreThanTheDataPayload() {
        assertThat(FrameType.values())
                .allSatisfy(type -> assertThat(type.maxPayload()).isLessThanOrEqualTo(FrameLimits.MAX_DATA_PAYLOAD));
    }
}
