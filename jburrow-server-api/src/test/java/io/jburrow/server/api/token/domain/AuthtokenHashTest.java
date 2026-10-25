package io.jburrow.server.api.token.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class AuthtokenHashTest {

    /** The documented hash of the local development token (ARCHITECTURE.md, CONTRIBUTING.md, application-local). */
    static final String LOCAL_TOKEN_HASH = "4e17d8906601750429e1cb705e2457eb786a348254390c98f634325c899d825f";

    private static final Authtoken LOCAL =
            Authtoken.parse(AuthtokenTest.LOCAL_TOKEN).orElseThrow();

    @Test
    void isTheSha256OfTheTokenText() {
        assertThat(AuthtokenHash.of(LOCAL).toHex()).isEqualTo(LOCAL_TOKEN_HASH);
    }

    @Test
    void roundTripsThroughHex() {
        assertThat(AuthtokenHash.fromHex(LOCAL_TOKEN_HASH).toHex()).isEqualTo(LOCAL_TOKEN_HASH);
    }

    @Test
    void matchesAnEqualHash() {
        assertThat(AuthtokenHash.of(LOCAL).matches(AuthtokenHash.fromHex(LOCAL_TOKEN_HASH)))
                .isTrue();
    }

    @Test
    void doesNotMatchADifferentHash() {
        AuthtokenHash other = AuthtokenHash.fromHex("0".repeat(64));

        assertThat(AuthtokenHash.of(LOCAL).matches(other)).isFalse();
    }

    @Test
    void comparesByMatchesOnly() {
        AuthtokenHash one = AuthtokenHash.fromHex(LOCAL_TOKEN_HASH);
        AuthtokenHash two = AuthtokenHash.fromHex(LOCAL_TOKEN_HASH);

        assertThat(one.matches(two)).isTrue();
        assertThat(one).isNotEqualTo(two);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "",
                "4E17D8906601750429E1CB705E2457EB786A348254390C98F634325C899D825F",
                "4e17d8906601750429e1cb705e2457eb786a348254390c98f634325c899d825",
                "4e17d8906601750429e1cb705e2457eb786a348254390c98f634325c899d825f0",
                "4e17d8906601750429e1cb705e2457eb786a348254390c98f634325c899d825g",
                " 4e17d8906601750429e1cb705e2457eb786a348254390c98f634325c899d825f"
            })
    void rejectsAnythingButSixtyFourLowerCaseHexCharacters(String hex) {
        assertThatIllegalArgumentException().isThrownBy(() -> AuthtokenHash.fromHex(hex));
    }
}
