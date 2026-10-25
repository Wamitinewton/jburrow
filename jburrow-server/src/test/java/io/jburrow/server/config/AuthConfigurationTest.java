package io.jburrow.server.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class AuthConfigurationTest {

    private static final String LOCAL_TOKEN = "jb_LOCAL-DEVELOPMENT-ONLY-unlocks-localhost-00";
    private static final String LOCAL_HASH = "4e17d8906601750429e1cb705e2457eb786a348254390c98f634325c899d825f";

    @Test
    void theLocalEntryUnlocksTheDocumentedToken() {
        assertThat(AuthConfiguration.staticVerifier(List.of("local:" + LOCAL_HASH))
                        .verify(LOCAL_TOKEN))
                .contains("local");
    }

    @Test
    void noEntriesRefuseEveryToken() {
        assertThat(AuthConfiguration.staticVerifier(List.of()).verify(LOCAL_TOKEN))
                .isEmpty();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "local",
                "Local:" + LOCAL_HASH,
                "local:4E17D8906601750429E1CB705E2457EB786A348254390C98F634325C899D825F",
                "local:4e17d8906601750429e1cb705e2457eb786a348254390c98f634325c899d825",
                "${JBURROW_AUTH_STATIC_TOKENS}"
            })
    void aMalformedEntryFailsNamingTheKeyAndPosition(String entry) {
        assertThatIllegalStateException()
                .isThrownBy(() -> AuthConfiguration.staticVerifier(List.of("alice:" + "0".repeat(64), entry)))
                .withMessageContaining("jburrow.auth.static-tokens")
                .withMessageContaining("JBURROW_AUTH_STATIC_TOKENS")
                .withMessageContaining("entry 2");
    }

    @ParameterizedTest
    @ValueSource(strings = {LOCAL_TOKEN, "alice:" + LOCAL_TOKEN})
    void aPastedTokenIsNeverRepeated(String entry) {
        assertThatIllegalStateException()
                .isThrownBy(() -> AuthConfiguration.staticVerifier(List.of(entry)))
                .withMessageNotContaining(LOCAL_TOKEN)
                .satisfies(e -> assertThat(e.getCause()).hasMessageNotContaining(LOCAL_TOKEN));
    }

    @Test
    void duplicateNamesFailNamingTheKey() {
        assertThatIllegalStateException()
                .isThrownBy(() ->
                        AuthConfiguration.staticVerifier(List.of("alice:" + LOCAL_HASH, "alice:" + "0".repeat(64))))
                .withMessageContaining("jburrow.auth.static-tokens")
                .withMessageContaining("alice");
    }

    @Test
    void duplicateHashesFailNamingTheKey() {
        assertThatIllegalStateException()
                .isThrownBy(() -> AuthConfiguration.staticVerifier(List.of("alice:" + LOCAL_HASH, "bob:" + LOCAL_HASH)))
                .withMessageContaining("jburrow.auth.static-tokens");
    }
}
