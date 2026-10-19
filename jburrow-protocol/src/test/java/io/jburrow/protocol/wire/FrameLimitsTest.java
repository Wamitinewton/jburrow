package io.jburrow.protocol.wire;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.EnumSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class FrameLimitsTest {

    @Test
    void sizesMatchTheProtocolLimitsTable() {
        assertThat(FrameLimits.HEADER_LENGTH).isEqualTo(10);
        assertThat(FrameLimits.MAX_DATA_PAYLOAD).isEqualTo(65_536);
        assertThat(FrameLimits.MAX_CONTROL_PAYLOAD).isEqualTo(16_384);
        assertThat(FrameLimits.MAX_MESSAGE_LENGTH).isEqualTo(65_546);
        assertThat(FrameLimits.PING_PAYLOAD_LENGTH).isEqualTo(8);
        assertThat(FrameLimits.WINDOW_UPDATE_PAYLOAD_LENGTH).isEqualTo(4);
        assertThat(FrameLimits.MAX_ERROR_CODE_LENGTH).isEqualTo(64);
    }

    @ParameterizedTest
    @EnumSource(
            value = FrameType.class,
            names = {"HELLO", "REGISTER", "OPEN", "HEADERS", "GOAWAY", "ERROR"})
    void controlPayloadsAllowZeroToSixteenKiB(FrameType type) {
        assertThat(FrameLimits.lengthAllowed(type, 0, 0)).isTrue();
        assertThat(FrameLimits.lengthAllowed(type, 0, 16_384)).isTrue();
        assertThat(FrameLimits.lengthAllowed(type, 0, 16_385)).isFalse();
        assertThat(FrameLimits.lengthAllowed(type, 0, -1)).isFalse();
    }

    @Test
    void dataCarriesOneToSixtyFourKiB() {
        assertThat(FrameLimits.lengthAllowed(FrameType.DATA, 0, 0)).isFalse();
        assertThat(FrameLimits.lengthAllowed(FrameType.DATA, 0, 1)).isTrue();
        assertThat(FrameLimits.lengthAllowed(FrameType.DATA, 0, 65_536)).isTrue();
        assertThat(FrameLimits.lengthAllowed(FrameType.DATA, 0, 65_537)).isFalse();
    }

    @Test
    void fixedLengthTypesAllowExactlyTheirLength() {
        for (FrameType type : EnumSet.of(FrameType.PING, FrameType.PONG)) {
            assertThat(FrameLimits.lengthAllowed(type, 0, 7)).isFalse();
            assertThat(FrameLimits.lengthAllowed(type, 0, 8)).isTrue();
            assertThat(FrameLimits.lengthAllowed(type, 0, 9)).isFalse();
        }
        assertThat(FrameLimits.lengthAllowed(FrameType.WINDOW_UPDATE, 0, 3)).isFalse();
        assertThat(FrameLimits.lengthAllowed(FrameType.WINDOW_UPDATE, 0, 4)).isTrue();
        assertThat(FrameLimits.lengthAllowed(FrameType.WINDOW_UPDATE, 0, 5)).isFalse();
    }

    @Test
    void halfCloseIsEmpty() {
        assertThat(FrameLimits.lengthAllowed(FrameType.CLOSE, 0, 0)).isTrue();
        assertThat(FrameLimits.lengthAllowed(FrameType.CLOSE, 0, 1)).isFalse();
    }

    @Test
    void resetCarriesAnErrorCodeOfOneToSixtyFourBytes() {
        assertThat(FrameLimits.lengthAllowed(FrameType.CLOSE, FrameFlags.RST, 0))
                .isFalse();
        assertThat(FrameLimits.lengthAllowed(FrameType.CLOSE, FrameFlags.RST, 1))
                .isTrue();
        assertThat(FrameLimits.lengthAllowed(FrameType.CLOSE, FrameFlags.RST, 64))
                .isTrue();
        assertThat(FrameLimits.lengthAllowed(FrameType.CLOSE, FrameFlags.RST, 65))
                .isFalse();
    }

    @Test
    void lengthsBeyondAnIntAreRejected() {
        long unsignedMax = 0xFFFF_FFFFL;
        for (FrameType type : FrameType.values()) {
            assertThat(FrameLimits.lengthAllowed(type, FrameFlags.definedFor(type), unsignedMax))
                    .as("%s", type)
                    .isFalse();
        }
    }

    @Test
    void onlyCloseDefinesAFlag() {
        for (FrameType type : FrameType.values()) {
            int expected = type == FrameType.CLOSE ? 0x01 : 0x00;
            assertThat(FrameFlags.definedFor(type)).as("%s", type).isEqualTo(expected);
        }
        assertThat(FrameFlags.RST).isEqualTo(0x01);
    }
}
