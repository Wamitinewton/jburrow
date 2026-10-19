package io.jburrow.protocol.control;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import io.jburrow.protocol.wire.ErrorCode;
import io.jburrow.protocol.wire.ErrorScope;
import io.jburrow.protocol.wire.ProtocolException;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;

final class ValidationAssertions {

    private ValidationAssertions() {}

    /** {@code construction} fails as a {@code VALIDATION_FAILED} session error with exactly {@code message}. */
    static void assertInvalid(ThrowingCallable construction, String message) {
        assertThatExceptionOfType(ProtocolException.class)
                .isThrownBy(construction)
                .withMessage(message)
                .satisfies(e -> {
                    assertThat(e.code()).isEqualTo(ErrorCode.VALIDATION_FAILED);
                    assertThat(e.scope()).isEqualTo(ErrorScope.SESSION);
                });
    }
}
