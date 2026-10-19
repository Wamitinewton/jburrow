package io.jburrow.server;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * Protocol and REST error codes share one code space but live in two enums, because a client never sees
 * {@code jburrow-server-api}. This module sees both, so it keeps the overlap to a declared set.
 */
class ErrorCodeSpaceTest {

    static final Set<String> SHARED = Set.of("NOT_FOUND", "VALIDATION_FAILED", "INTERNAL_ERROR");

    @Test
    void namesSharedByBothEnumsAreExactlyTheDeclaredSet() {
        Set<String> protocol = names(io.jburrow.protocol.wire.ErrorCode.values());
        Set<String> rest = names(io.jburrow.server.api.shared.error.ErrorCode.values());

        Set<String> overlap = new TreeSet<>(protocol);
        overlap.retainAll(rest);

        assertThat(overlap)
                .as("""
                        The protocol and REST ErrorCode enums share these names: %s. Expected exactly %s.
                        A name in both enums must mean the same error. For each difference, decide:
                        - the same error in both places: add the name to ErrorCodeSpaceTest.SHARED;
                        - a different error: rename one of the constants so the codes don't collide;
                        - no longer shared: remove it from SHARED.
                        """.formatted(overlap, new TreeSet<>(SHARED)))
                .containsExactlyInAnyOrderElementsOf(SHARED);
    }

    private static Set<String> names(Enum<?>[] constants) {
        return Arrays.stream(constants).map(Enum::name).collect(Collectors.toSet());
    }
}
