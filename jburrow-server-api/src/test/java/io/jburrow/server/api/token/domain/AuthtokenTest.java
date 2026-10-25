package io.jburrow.server.api.token.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class AuthtokenTest {

    static final String LOCAL_TOKEN = "jb_LOCAL-DEVELOPMENT-ONLY-unlocks-localhost-00";

    @ParameterizedTest
    @ValueSource(
            strings = {
                LOCAL_TOKEN,
                "jb_AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
                "jb_0123456789abcdefghijklmnopqrstuvwxyz_-ABCDE"
            })
    void acceptsTheFormat(String raw) {
        assertThat(Authtoken.parse(raw)).isPresent();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "",
                "jb_",
                "jb_LOCAL-DEVELOPMENT-ONLY-unlocks-localhost-0",
                "jb_LOCAL-DEVELOPMENT-ONLY-unlocks-localhost-000",
                "jb_LOCAL-DEVELOPMENT-ONLY-unlocks-localhost+0",
                "jb_LOCAL-DEVELOPMENT-ONLY-unlocks-localhost/0",
                "jb_LOCAL-DEVELOPMENT-ONLY-unlocks-localhost=0",
                "jb_LOCAL-DEVELOPMENT-ONLY-unlocks-localhost 0",
                "xx_LOCAL-DEVELOPMENT-ONLY-unlocks-localhost-00",
                "JB_LOCAL-DEVELOPMENT-ONLY-unlocks-localhost-00",
                "LOCAL-DEVELOPMENT-ONLY-unlocks-localhost-00aaa",
                " jb_LOCAL-DEVELOPMENT-ONLY-unlocks-localhost-00",
                "jb_LOCAL-DEVELOPMENT-ONLY-unlocks-localhost-00 ",
                "jb_LOCAL-DEVELOPMENT-ONLY-unlocks-localhost-00\n",
                "jb_LOCAL-DEVELOPMENT-ONLY-unlocks-localhost-0é"
            })
    void rejectsAnythingElse(String raw) {
        assertThat(Authtoken.parse(raw)).isEmpty();
    }

    @Test
    void rejectsNull() {
        assertThat(Authtoken.parse(null)).isEmpty();
    }

    @Test
    void rejectsAHugeInputQuickly() {
        String huge = "jb_" + "A".repeat(10_000);
        long started = System.nanoTime();

        assertThat(Authtoken.parse(huge)).isEmpty();

        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofMillis(50));
    }

    @Test
    void toStringHidesTheSecret() {
        Authtoken token = Authtoken.parse(LOCAL_TOKEN).orElseThrow();

        assertThat(token).hasToString("jb_***");
    }

    @Test
    void hashesTheFullToken() {
        Authtoken token = Authtoken.parse(LOCAL_TOKEN).orElseThrow();

        assertThat(token.hash().toHex()).isEqualTo(AuthtokenHashTest.LOCAL_TOKEN_HASH);
    }
}
