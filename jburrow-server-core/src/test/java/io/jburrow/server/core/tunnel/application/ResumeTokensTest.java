package io.jburrow.server.core.tunnel.application;

import static org.assertj.core.api.Assertions.assertThat;

import io.jburrow.server.core.tunnel.domain.ResumeTokenHash;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ResumeTokensTest {

    private final ResumeTokens tokens = new ResumeTokens();

    @Test
    void issuesDistinctTokens() {
        Set<String> values = new HashSet<>();
        for (int i = 0; i < 1_000; i++) {
            values.add(tokens.issue().token().value());
        }

        assertThat(values).hasSize(1_000);
    }

    @Test
    void theHashIsTheTokensSha256() {
        ResumeTokens.Issued issued = tokens.issue();

        assertThat(issued.hash()).isEqualTo(ResumeTokenHash.of(issued.token().sha256()));
        assertThat(issued.hash()).isEqualTo(ResumeTokenHash.of(issued.token()));
    }

    @Test
    void neitherTheTokenNorItsHashAppearsInText() {
        ResumeTokens.Issued issued = tokens.issue();

        assertThat(issued.toString()).doesNotContain(issued.token().value().substring(4));
        assertThat(issued.hash().toString()).isEqualTo("ResumeTokenHash[***]");
    }
}
