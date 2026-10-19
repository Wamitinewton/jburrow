package io.jburrow.protocol.control;

import static io.jburrow.protocol.control.ValidationAssertions.assertInvalid;
import static org.assertj.core.api.Assertions.assertThat;

import io.jburrow.protocol.wire.ErrorCode;
import io.jburrow.protocol.wire.ErrorScope;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class ErrorBodyTest {

    @Test
    void acceptsTheArchitectureExample() {
        ErrorBody body = new ErrorBody(ErrorCode.PORT_UNAVAILABLE, "Port 20417 is already in use.", "default");

        assertThat(body.ref()).isEqualTo("default");
    }

    @Test
    void refIsOptional() {
        assertThat(new ErrorBody(ErrorCode.PROTOCOL_ERROR, "x", null).ref()).isNull();
    }

    @ParameterizedTest
    @EnumSource(ErrorCode.class)
    void acceptsExactlyTheSessionAndUpgradeCodes(ErrorCode code) {
        boolean allowed =
                code.scopes().contains(ErrorScope.SESSION) || code.scopes().contains(ErrorScope.UPGRADE);

        if (allowed) {
            assertThat(new ErrorBody(code, "x", null).code()).isEqualTo(code);
        } else {
            assertInvalid(() -> new ErrorBody(code, "x", null), "code must be a session or upgrade error code");
        }
    }

    @Test
    void codeAndMessageAreRequired() {
        assertInvalid(() -> new ErrorBody(null, "x", null), "code is required");
        assertInvalid(() -> new ErrorBody(ErrorCode.PROTOCOL_ERROR, null, null), "message is required");
    }

    @Test
    void messageIsOneTo512Characters() {
        new ErrorBody(ErrorCode.PROTOCOL_ERROR, "m".repeat(512), null);
        String rule = "message must be 1 to 512 characters";
        assertInvalid(() -> new ErrorBody(ErrorCode.PROTOCOL_ERROR, "", null), rule);
        assertInvalid(() -> new ErrorBody(ErrorCode.PROTOCOL_ERROR, "m".repeat(513), null), rule);
    }

    @Test
    void refFollowsTheTunnelRefRule() {
        assertInvalid(
                () -> new ErrorBody(ErrorCode.PORT_UNAVAILABLE, "x", "Default"),
                "ref must match [a-z0-9][a-z0-9-]{0,31}");
    }
}
