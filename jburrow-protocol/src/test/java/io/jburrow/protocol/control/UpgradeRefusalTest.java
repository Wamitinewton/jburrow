package io.jburrow.protocol.control;

import static io.jburrow.protocol.control.ValidationAssertions.assertInvalid;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import io.jburrow.protocol.wire.ErrorCode;
import io.jburrow.protocol.wire.ErrorScope;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class UpgradeRefusalTest {

    private static final Instant AT = Instant.parse("2026-10-19T10:15:30.250Z");

    @Test
    void formatsTheTimestampAsAnIsoInstant() {
        UpgradeRefusal refusal = UpgradeRefusal.of(ErrorCode.NOT_FOUND, "Not here.", AT, "/");

        assertThat(refusal.timestamp()).isEqualTo("2026-10-19T10:15:30.250Z");
        assertThat(refusal.success()).isFalse();
        assertThat(refusal.supported()).isNull();
    }

    @ParameterizedTest
    @EnumSource(ErrorCode.class)
    void acceptsExactlyTheUpgradeCodes(ErrorCode code) {
        if (code.scopes().contains(ErrorScope.UPGRADE)) {
            assertThat(new UpgradeRefusal(false, code, "x", null, "t", "/").code())
                    .isEqualTo(code);
        } else {
            assertInvalid(
                    () -> new UpgradeRefusal(false, code, "x", null, "t", "/"), "code must be an upgrade error code");
        }
    }

    @Test
    void successIsAlwaysFalse() {
        assertInvalid(
                () -> new UpgradeRefusal(true, ErrorCode.NOT_FOUND, "x", null, "t", "/"), "success must be false");
    }

    @Test
    void requiresItsFields() {
        assertInvalid(() -> new UpgradeRefusal(false, null, "x", null, "t", "/"), "code is required");
        assertInvalid(
                () -> new UpgradeRefusal(false, ErrorCode.NOT_FOUND, null, null, "t", "/"), "message is required");
        assertInvalid(
                () -> new UpgradeRefusal(false, ErrorCode.NOT_FOUND, "x", null, null, "/"), "timestamp is required");
        assertInvalid(() -> new UpgradeRefusal(false, ErrorCode.NOT_FOUND, "x", null, "t", null), "path is required");
    }

    @Test
    void supportedIsCopiedAndHasNoNulls() {
        List<String> supported = new ArrayList<>(List.of("jburrow.v1-draft.1"));
        UpgradeRefusal refusal = UpgradeRefusal.unsupportedVersion("x", supported, AT, "/v1/connect");
        supported.clear();

        assertThat(refusal.supported()).containsExactly("jburrow.v1-draft.1");
        assertInvalid(
                () -> UpgradeRefusal.unsupportedVersion("x", Arrays.asList("a", null), AT, "/"),
                "supported[1] is required");
    }

    @Test
    void anUnsupportedVersionRefusalNeedsItsList() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> UpgradeRefusal.of(ErrorCode.UNSUPPORTED_PROTOCOL_VERSION, "x", AT, "/"));
    }
}
