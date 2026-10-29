package io.jburrow.protocol.wire;

import static io.jburrow.protocol.wire.ErrorScope.SESSION;
import static io.jburrow.protocol.wire.ErrorScope.STREAM;
import static io.jburrow.protocol.wire.ErrorScope.UPGRADE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import java.util.OptionalInt;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

class ErrorCodeTest {

    private static final int NONE = -1;

    /** {@code docs/protocol.md} §12.2, row for row: scopes, upgrade status, close code. */
    static Stream<Arguments> registry() {
        return Stream.of(
                arguments(ErrorCode.NOT_FOUND, Set.of(UPGRADE), 404, NONE),
                arguments(ErrorCode.FORBIDDEN_ORIGIN, Set.of(UPGRADE), 403, NONE),
                arguments(ErrorCode.UNSUPPORTED_PROTOCOL_VERSION, Set.of(UPGRADE), 400, NONE),
                arguments(ErrorCode.SERVER_AT_CAPACITY, Set.of(UPGRADE), 503, NONE),
                arguments(ErrorCode.SERVER_SHUTTING_DOWN, Set.of(UPGRADE, SESSION), 503, 1001),
                arguments(ErrorCode.PROTOCOL_ERROR, Set.of(UPGRADE, SESSION), 400, 1002),
                arguments(ErrorCode.FRAME_SIZE_ERROR, Set.of(SESSION), NONE, 1002),
                arguments(ErrorCode.FLOW_CONTROL_ERROR, Set.of(SESSION), NONE, 1002),
                arguments(ErrorCode.HANDSHAKE_TIMEOUT, Set.of(SESSION), NONE, 1008),
                arguments(ErrorCode.AUTHTOKEN_INVALID, Set.of(SESSION), NONE, 1008),
                arguments(ErrorCode.VALIDATION_FAILED, Set.of(SESSION), NONE, 1008),
                arguments(ErrorCode.TUNNEL_LIMIT_EXCEEDED, Set.of(SESSION), NONE, 1008),
                arguments(ErrorCode.SESSION_LIMIT_EXCEEDED, Set.of(SESSION), NONE, 1008),
                arguments(ErrorCode.PORT_UNAVAILABLE, Set.of(SESSION), NONE, 1008),
                arguments(ErrorCode.PORT_POOL_EXHAUSTED, Set.of(SESSION), NONE, 1008),
                arguments(ErrorCode.HEARTBEAT_TIMEOUT, Set.of(SESSION), NONE, 1008),
                arguments(ErrorCode.PING_RATE_EXCEEDED, Set.of(SESSION), NONE, 1008),
                arguments(ErrorCode.INTERNAL_ERROR, Set.of(SESSION, STREAM), NONE, 1011),
                arguments(ErrorCode.PEER_RESET, Set.of(STREAM), NONE, NONE),
                arguments(ErrorCode.LOCAL_SERVICE_UNREACHABLE, Set.of(STREAM), NONE, NONE),
                arguments(ErrorCode.STREAM_CLOSED, Set.of(STREAM), NONE, NONE),
                arguments(ErrorCode.CANCELLED, Set.of(STREAM), NONE, NONE));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("registry")
    void matchesTheArchitectureTable(ErrorCode code, Set<ErrorScope> scopes, int upgradeStatus, int closeCode) {
        assertThat(code.scopes()).containsExactlyInAnyOrderElementsOf(scopes);
        assertThat(code.upgradeStatus()).isEqualTo(optional(upgradeStatus));
        assertThat(code.closeCode()).isEqualTo(optional(closeCode));
    }

    @Test
    void registryHasNoOtherCodes() {
        assertThat(registry()).hasSameSizeAs(ErrorCode.values());
    }

    @ParameterizedTest
    @EnumSource(ErrorCode.class)
    void codeIsTheConstantNameInWireFormat(ErrorCode code) {
        assertThat(code.code()).isEqualTo(code.name()).matches("[A-Z0-9_]{1,64}");
        assertThat(code.code().length()).isLessThanOrEqualTo(FrameLimits.MAX_ERROR_CODE_LENGTH);
    }

    @ParameterizedTest
    @EnumSource(ErrorCode.class)
    void fromCodeRoundTrips(ErrorCode code) {
        assertThat(ErrorCode.fromCode(code.code())).contains(code);
    }

    @Test
    void fromCodeIsExact() {
        assertThat(ErrorCode.fromCode("not_found")).isEmpty();
        assertThat(ErrorCode.fromCode("Not_Found")).isEmpty();
        assertThat(ErrorCode.fromCode(" NOT_FOUND")).isEmpty();
        assertThat(ErrorCode.fromCode("NOT_FOUND ")).isEmpty();
        assertThat(ErrorCode.fromCode("NO_SUCH_CODE")).isEmpty();
        assertThat(ErrorCode.fromCode("")).isEmpty();
        assertThat(ErrorCode.fromCode(null)).isEmpty();
    }

    @Test
    void scopesAreImmutable() {
        assertThat(ErrorCode.INTERNAL_ERROR.scopes()).isUnmodifiable();
    }

    private static OptionalInt optional(int value) {
        return value == NONE ? OptionalInt.empty() : OptionalInt.of(value);
    }
}
