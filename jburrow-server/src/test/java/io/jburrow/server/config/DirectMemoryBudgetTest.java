package io.jburrow.server.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import io.jburrow.server.core.tunnel.adapter.netty.edge.EdgeTransport;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;

/** The arithmetic of {@code docs/phase-2-multiplexing/ARCHITECTURE.md} §Memory budget. */
class DirectMemoryBudgetTest {

    private static final long MIB = 1024 * 1024;
    private static final long LIMIT = 256 * MIB;

    @Test
    void theDefaultsMatchTheArchitecture() {
        DirectMemoryBudget budget = DirectMemoryBudget.of(edge(DataSize.ofKilobytes(512)), LIMIT);

        assertThat(budget.perSession()).isEqualTo(512 * 1024 + 64 * 1024 + 256 * 1024 + 64 * 1024 + 1_000);
        assertThat(budget.sessions()).isEqualTo(200);
        assertThat(budget.handshakes()).isEqualTo(64);
        assertThat(budget.total()).isEqualTo(200 * budget.perSession() + 64 * 64 * 1024);
        assertThat(budget.share()).isBetween(0.69, 0.71);
        assertThat(budget.exceedsWarnShare()).isFalse();
        assertThat(budget.describe())
                .isEqualTo("200 sessions × 897 KiB + 64 handshakes × 64 KiB = 179 MiB of 256 MiB (70%)");
    }

    @Test
    void warnsAboveNinetyPercentOfTheLimit() {
        DirectMemoryBudget defaults = DirectMemoryBudget.of(edge(DataSize.ofKilobytes(512)), LIMIT);
        long justUnder = (long) Math.ceil(defaults.total() / DirectMemoryBudget.WARN_SHARE);

        assertThat(DirectMemoryBudget.of(edge(DataSize.ofKilobytes(512)), justUnder)
                        .exceedsWarnShare())
                .isFalse();
        assertThat(DirectMemoryBudget.of(edge(DataSize.ofKilobytes(512)), justUnder - 1)
                        .exceedsWarnShare())
                .isTrue();
    }

    @Test
    void aLargeSessionWindowExceedsTheLimit() {
        DirectMemoryBudget budget = DirectMemoryBudget.of(edge(DataSize.ofMegabytes(64)), LIMIT);

        assertThat(budget.exceedsWarnShare()).isTrue();
        assertThat(budget.share()).isGreaterThan(1);
        assertThat(budget.describe()).startsWith("200 sessions × 65921 KiB").endsWith("%)");
    }

    @Test
    void theLimitIsPositive() {
        assertThatIllegalArgumentException().isThrownBy(() -> new DirectMemoryBudget(1, 1, 1, 0));
    }

    private static JBurrowProperties.Edge edge(DataSize sessionWindow) {
        return new JBurrowProperties.Edge(
                7080,
                "0.0.0.0",
                "connect.example.dev",
                EdgeTransport.AUTO,
                null,
                List.of(),
                200,
                64,
                3,
                Duration.ofSeconds(10),
                Duration.ofSeconds(10),
                Duration.ofSeconds(15),
                Duration.ofSeconds(45),
                "64KB-256KB",
                DataSize.ofKilobytes(256),
                sessionWindow,
                1_000,
                20_000);
    }
}
