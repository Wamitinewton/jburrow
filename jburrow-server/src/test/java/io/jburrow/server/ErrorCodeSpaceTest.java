package io.jburrow.server;

import static org.assertj.core.api.Assertions.assertThat;

import io.jburrow.server.core.tunnel.adapter.netty.http.EdgeError;
import java.util.Arrays;
import java.util.Optional;
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

    /**
     * A code a protocol capability introduces is unknown to a peer without it, so the REST API, which no such peer
     * negotiates with, never shares one.
     */
    @Test
    void codesACapabilityIntroducesAreNeverShared() {
        Set<String> rest = names(io.jburrow.server.api.shared.error.ErrorCode.values());

        assertThat(Arrays.stream(io.jburrow.protocol.wire.ErrorCode.values())
                        .filter(code -> code.capability().isPresent())
                        .map(Enum::name))
                .containsExactlyInAnyOrder("SUBDOMAIN_UNAVAILABLE", "SUBDOMAIN_FORBIDDEN", "LOCAL_SERVICE_ERROR")
                .noneMatch(rest::contains);
    }

    /**
     * The edge's page codes reach visitors, never a peer, so they don't need a protocol constant; a page may share a
     * protocol code's name only to report that code, as {@link EdgeError#protocolCode()} declares.
     */
    @Test
    void edgeErrorCodesShareANameOnlyWithTheProtocolCodeTheyRender() {
        Set<String> rest = names(io.jburrow.server.api.shared.error.ErrorCode.values());

        for (EdgeError error : EdgeError.values()) {
            Optional<io.jburrow.protocol.wire.ErrorCode> sameName = Arrays.stream(
                            io.jburrow.protocol.wire.ErrorCode.values())
                    .filter(code -> code.name().equals(error.name()))
                    .findFirst();

            assertThat(rest).as("REST codes").doesNotContain(error.name());
            assertThat(error.protocolCode())
                    .as("%s shares a name with a protocol code only if it renders that code", error)
                    .isEqualTo(sameName);
        }
    }

    private static Set<String> names(Enum<?>[] constants) {
        return Arrays.stream(constants).map(Enum::name).collect(Collectors.toSet());
    }
}
