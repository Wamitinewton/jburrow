package io.jburrow.protocol.wire;

import static io.jburrow.protocol.wire.ErrorCode.Unset.NONE;
import static io.jburrow.protocol.wire.ErrorScope.SESSION;
import static io.jburrow.protocol.wire.ErrorScope.STREAM;
import static io.jburrow.protocol.wire.ErrorScope.UPGRADE;

import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The protocol's error code registry. The constant name is the code on the wire, and a released constant is never
 * renamed or given a new meaning.
 *
 * <p>The REST API has its own enum in {@code jburrow-server-api}, which a client never sees. Both draw from one code
 * space: a name they share means the same error, and {@code ErrorCodeSpaceTest} in {@code jburrow-server} keeps the
 * overlap to a declared set.
 */
public enum ErrorCode {
    NOT_FOUND(Set.of(UPGRADE), 404, NONE),
    FORBIDDEN_ORIGIN(Set.of(UPGRADE), 403, NONE),
    UNSUPPORTED_PROTOCOL_VERSION(Set.of(UPGRADE), 400, NONE),
    SERVER_AT_CAPACITY(Set.of(UPGRADE), 503, NONE),
    SERVER_SHUTTING_DOWN(Set.of(UPGRADE, SESSION), 503, 1001),
    PROTOCOL_ERROR(Set.of(UPGRADE, SESSION), 400, 1002),
    FRAME_SIZE_ERROR(Set.of(SESSION), NONE, 1002),
    HANDSHAKE_TIMEOUT(Set.of(SESSION), NONE, 1008),
    AUTHTOKEN_INVALID(Set.of(SESSION), NONE, 1008),
    VALIDATION_FAILED(Set.of(SESSION), NONE, 1008),
    TUNNEL_LIMIT_EXCEEDED(Set.of(SESSION), NONE, 1008),
    SESSION_LIMIT_EXCEEDED(Set.of(SESSION), NONE, 1008),
    PORT_UNAVAILABLE(Set.of(SESSION), NONE, 1008),
    PORT_POOL_EXHAUSTED(Set.of(SESSION), NONE, 1008),
    HEARTBEAT_TIMEOUT(Set.of(SESSION), NONE, 1008),
    PING_RATE_EXCEEDED(Set.of(SESSION), NONE, 1008),
    INTERNAL_ERROR(Set.of(SESSION, STREAM), NONE, 1011),
    PEER_RESET(Set.of(STREAM), NONE, NONE),
    LOCAL_SERVICE_UNREACHABLE(Set.of(STREAM), NONE, NONE),
    STREAM_CLOSED(Set.of(STREAM), NONE, NONE),
    CANCELLED(Set.of(STREAM), NONE, NONE);

    /** Holds the "no value" marker apart from the enum, since an enum constant can't forward-reference its own field. */
    static final class Unset {
        static final int NONE = -1;

        private Unset() {}
    }

    private static final Map<String, ErrorCode> BY_CODE =
            Arrays.stream(values()).collect(Collectors.toUnmodifiableMap(ErrorCode::name, Function.identity()));

    private final Set<ErrorScope> scopes;
    private final OptionalInt upgradeStatus;
    private final OptionalInt closeCode;

    ErrorCode(Set<ErrorScope> scopes, int upgradeStatus, int closeCode) {
        if (scopes.contains(UPGRADE) != (upgradeStatus != NONE)) {
            throw new IllegalArgumentException(name() + ": an upgrade status is required exactly for UPGRADE scope");
        }
        if (scopes.contains(SESSION) != (closeCode != NONE)) {
            throw new IllegalArgumentException(name() + ": a close code is required exactly for SESSION scope");
        }
        this.scopes = Collections.unmodifiableSet(EnumSet.copyOf(scopes));
        this.upgradeStatus = upgradeStatus == NONE ? OptionalInt.empty() : OptionalInt.of(upgradeStatus);
        this.closeCode = closeCode == NONE ? OptionalInt.empty() : OptionalInt.of(closeCode);
    }

    public Set<ErrorScope> scopes() {
        return scopes;
    }

    /** The HTTP status of a refused upgrade; empty unless the scopes contain {@link ErrorScope#UPGRADE}. */
    public OptionalInt upgradeStatus() {
        return upgradeStatus;
    }

    /** The advisory WebSocket close code; empty unless the scopes contain {@link ErrorScope#SESSION}. */
    public OptionalInt closeCode() {
        return closeCode;
    }

    public String code() {
        return name();
    }

    /** The constant for a code read from the wire. The match is exact: no case folding, no trimming. */
    public static Optional<ErrorCode> fromCode(String code) {
        return code == null ? Optional.empty() : Optional.ofNullable(BY_CODE.get(code));
    }
}
