package io.jburrow.server.core.tunnel.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.jburrow.protocol.control.ResumeToken;
import java.util.Random;
import org.junit.jupiter.api.Test;

class ResumeTokenHashTest {

    private final ResumeToken token = ResumeToken.generate(new Random(5));

    @Test
    void isEqualByContent() {
        assertThat(ResumeTokenHash.of(token))
                .isEqualTo(ResumeTokenHash.of(token.sha256()))
                .hasSameHashCodeAs(ResumeTokenHash.of(token.sha256()))
                .isNotEqualTo(ResumeTokenHash.of(ResumeToken.generate(new Random(6))));
    }

    @Test
    void keepsItsOwnCopy() {
        byte[] sha256 = token.sha256();
        ResumeTokenHash hash = ResumeTokenHash.of(sha256);

        sha256[0]++;

        assertThat(hash).isEqualTo(ResumeTokenHash.of(token));
    }

    @Test
    void isThirtyTwoBytes() {
        assertThatThrownBy(() -> ResumeTokenHash.of(new byte[31])).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void neverShowsTheHash() {
        assertThat(ResumeTokenHash.of(token)).hasToString("ResumeTokenHash[***]");
    }
}
