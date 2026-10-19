package io.jburrow.protocol.wire;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import org.junit.jupiter.api.Test;

class ProtocolExceptionTest {

    @Test
    void sessionErrorCarriesItsCodeAndMessage() {
        ProtocolException exception = ProtocolException.session(ErrorCode.FRAME_SIZE_ERROR, "DATA frame is empty.");

        assertThat(exception.code()).isEqualTo(ErrorCode.FRAME_SIZE_ERROR);
        assertThat(exception.scope()).isEqualTo(ErrorScope.SESSION);
        assertThat(exception).hasMessage("DATA frame is empty.");
    }

    @Test
    void streamErrorCarriesItsCodeAndMessage() {
        ProtocolException exception = ProtocolException.stream(ErrorCode.STREAM_CLOSED, "Stream 3 is half-closed.");

        assertThat(exception.code()).isEqualTo(ErrorCode.STREAM_CLOSED);
        assertThat(exception.scope()).isEqualTo(ErrorScope.STREAM);
    }

    @Test
    void codesInBothScopesWorkForBothUses() {
        assertThat(ProtocolException.session(ErrorCode.INTERNAL_ERROR, "x").scope())
                .isEqualTo(ErrorScope.SESSION);
        assertThat(ProtocolException.stream(ErrorCode.INTERNAL_ERROR, "x").scope())
                .isEqualTo(ErrorScope.STREAM);
    }

    @Test
    void sessionRejectsAStreamOnlyCode() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> ProtocolException.session(ErrorCode.PEER_RESET, "x"))
                .withMessageContaining("PEER_RESET");
    }

    @Test
    void sessionRejectsAnUpgradeOnlyCode() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> ProtocolException.session(ErrorCode.FORBIDDEN_ORIGIN, "x"));
    }

    @Test
    void streamRejectsASessionOnlyCode() {
        assertThatIllegalArgumentException().isThrownBy(() -> ProtocolException.stream(ErrorCode.PROTOCOL_ERROR, "x"));
    }

    @Test
    void streamRejectsAnUpgradeOnlyCode() {
        assertThatIllegalArgumentException().isThrownBy(() -> ProtocolException.stream(ErrorCode.NOT_FOUND, "x"));
    }

    @Test
    void requiresACodeAndAMessage() {
        assertThatNullPointerException().isThrownBy(() -> ProtocolException.session(null, "x"));
        assertThatNullPointerException().isThrownBy(() -> ProtocolException.session(ErrorCode.PROTOCOL_ERROR, null));
        assertThatIllegalArgumentException().isThrownBy(() -> ProtocolException.session(ErrorCode.PROTOCOL_ERROR, " "));
    }

    @Test
    void sessionErrorHasNoRefUntilItNamesATunnel() {
        ProtocolException exception = ProtocolException.session(ErrorCode.PORT_UNAVAILABLE, "Port 20417 is in use.");

        ProtocolException named = exception.forTunnel("default");

        assertThat(exception.ref()).isEmpty();
        assertThat(named.ref()).contains("default");
        assertThat(named.code()).isEqualTo(ErrorCode.PORT_UNAVAILABLE);
        assertThat(named.scope()).isEqualTo(ErrorScope.SESSION);
        assertThat(named).hasMessage("Port 20417 is in use.");
    }

    @Test
    void streamErrorCannotNameATunnel() {
        assertThatIllegalStateException()
                .isThrownBy(() ->
                        ProtocolException.stream(ErrorCode.PEER_RESET, "x").forTunnel("default"));
    }

    @Test
    void hasNoStackTrace() {
        ProtocolException exception = ProtocolException.session(ErrorCode.PROTOCOL_ERROR, "Reserved bit set.");

        assertThat(exception.getStackTrace()).isEmpty();
        assertThat(exception.fillInStackTrace().getStackTrace()).isEmpty();
    }

    @Test
    void doesNotCollectSuppressedExceptions() {
        ProtocolException exception = ProtocolException.session(ErrorCode.PROTOCOL_ERROR, "x");

        exception.addSuppressed(new IllegalStateException());

        assertThat(exception.getSuppressed()).isEmpty();
    }
}
