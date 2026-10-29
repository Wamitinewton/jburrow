package io.jburrow.protocol.flow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import io.jburrow.protocol.wire.ErrorCode;
import org.junit.jupiter.api.Test;

class DataOutcomeTest {

    @Test
    void aResetCarriesAStreamErrorAndItsCredit() {
        DataOutcome.Reset reset = new DataOutcome.Reset(ErrorCode.STREAM_CLOSED, new Credit(10, 0));

        assertThat(reset.code()).isEqualTo(ErrorCode.STREAM_CLOSED);
        assertThat(reset.credit()).isEqualTo(new Credit(10, 0));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new DataOutcome.Reset(ErrorCode.FLOW_CONTROL_ERROR, Credit.NONE));
        assertThatNullPointerException().isThrownBy(() -> new DataOutcome.Reset(ErrorCode.STREAM_CLOSED, null));
    }

    @Test
    void aDiscardCarriesItsCredit() {
        assertThat(new DataOutcome.Discard(Credit.NONE).credit()).isSameAs(Credit.NONE);
        assertThatNullPointerException().isThrownBy(() -> new DataOutcome.Discard(null));
    }

    @Test
    void deliveryIsShared() {
        assertThat(DataOutcome.DELIVER).isEqualTo(new DataOutcome.Deliver());
    }
}
