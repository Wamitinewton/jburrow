package io.jburrow.server.api.token.adapter.staticconfig;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import io.jburrow.server.api.token.domain.Authtoken;
import io.jburrow.server.api.token.domain.AuthtokenHash;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class StaticTokenEntryTest {

    /** Must match application-local.yaml, CONTRIBUTING.md and ARCHITECTURE.md §Authentication in phase 1. */
    static final String LOCAL_TOKEN = "jb_LOCAL-DEVELOPMENT-ONLY-unlocks-localhost-00";

    static final String LOCAL_ENTRY = "local:4e17d8906601750429e1cb705e2457eb786a348254390c98f634325c899d825f";

    private static final String HASH = "4e17d8906601750429e1cb705e2457eb786a348254390c98f634325c899d825f";

    @Test
    void theLocalEntryMatchesTheDocumentedLocalToken() {
        StaticTokenEntry entry = StaticTokenEntry.parse(LOCAL_ENTRY);
        Authtoken token = Authtoken.parse(LOCAL_TOKEN).orElseThrow();

        assertThat(entry.name()).isEqualTo("local");
        assertThat(entry.hash().matches(AuthtokenHash.of(token))).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"a", "alice", "ci-runner-2", "0day", "abcdefghijklmnopqrstuvwxyz012345"})
    void parsesValidNames(String name) {
        StaticTokenEntry entry = StaticTokenEntry.parse(name + ":" + HASH);

        assertThat(entry.name()).isEqualTo(name);
        assertThat(entry.hash().toHex()).isEqualTo(HASH);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {"", "-alice", "Alice", "al ice", "al_ice", "alice.dev", "abcdefghijklmnopqrstuvwxyz0123456"})
    void rejectsInvalidNames(String name) {
        assertThatIllegalArgumentException().isThrownBy(() -> StaticTokenEntry.parse(name + ":" + HASH));
    }

    @ParameterizedTest
    @ValueSource(strings = {"alice", "alice:", ":" + HASH, "alice:" + HASH + ":x", "alice:" + HASH + " "})
    void rejectsMalformedEntries(String entry) {
        assertThatIllegalArgumentException().isThrownBy(() -> StaticTokenEntry.parse(entry));
    }

    @Test
    void aPastedTokenIsNotEchoedInTheError() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> StaticTokenEntry.parse("alice:" + LOCAL_TOKEN))
                .withMessageNotContaining(LOCAL_TOKEN);
        assertThatIllegalArgumentException()
                .isThrownBy(() -> StaticTokenEntry.parse(LOCAL_TOKEN))
                .withMessageNotContaining(LOCAL_TOKEN);
    }
}
