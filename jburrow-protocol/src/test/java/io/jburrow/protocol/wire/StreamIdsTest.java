package io.jburrow.protocol.wire;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import org.junit.jupiter.api.Test;

class StreamIdsTest {

    @Test
    void reservedBitIsTheTopBit() {
        assertThat(StreamIds.isReservedBitSet(0x8000_0000)).isTrue();
        assertThat(StreamIds.isReservedBitSet(0x8000_0001)).isTrue();
        assertThat(StreamIds.isReservedBitSet(0xFFFF_FFFF)).isTrue();
        assertThat(StreamIds.isReservedBitSet(0)).isFalse();
        assertThat(StreamIds.isReservedBitSet(1)).isFalse();
        assertThat(StreamIds.isReservedBitSet(StreamIds.MAX)).isFalse();
    }

    @Test
    void maxIsThirtyOneBits() {
        assertThat(StreamIds.MAX).isEqualTo((1 << 31) - 1);
        assertThat(StreamIds.MAX & StreamIds.RESERVED_BIT).isZero();
    }

    @Test
    void firstStreamIsOne() {
        assertThat(StreamIds.next(StreamIds.CONTROL)).isEqualTo(1);
    }

    @Test
    void nextIncrementsByOne() {
        assertThat(StreamIds.next(1)).isEqualTo(2);
        assertThat(StreamIds.next(StreamIds.MAX - 1)).isEqualTo(StreamIds.MAX);
    }

    @Test
    void nextFailsOnceEveryIdIsUsed() {
        ProtocolException exception =
                catchThrowableOfType(ProtocolException.class, () -> StreamIds.next(StreamIds.MAX));

        assertThat(exception.code()).isEqualTo(ErrorCode.INTERNAL_ERROR);
        assertThat(exception.scope()).isEqualTo(ErrorScope.SESSION);
    }

    @Test
    void nextRejectsANegativeId() {
        assertThatIllegalArgumentException().isThrownBy(() -> StreamIds.next(-1));
    }
}
