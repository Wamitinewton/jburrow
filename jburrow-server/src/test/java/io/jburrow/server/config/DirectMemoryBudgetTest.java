package io.jburrow.server.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import io.jburrow.server.core.tunnel.adapter.netty.edge.EdgeTransport;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;

/**
 * The arithmetic of {@code docs/phase-2-multiplexing/ARCHITECTURE.md} §Memory budget, with the parked responses of
 * {@code docs/phase-3-http-tunnels/ARCHITECTURE.md} §Memory budget on top.
 */
class DirectMemoryBudgetTest {

    private static final long MIB = 1024 * 1024;
    private static final long LIMIT = 256 * MIB;

    @Test
    void theDefaultsMatchTheArchitecture() {
        DirectMemoryBudget budget =
                DirectMemoryBudget.of(edge(DataSize.ofKilobytes(512)), http(DataSize.ofMegabytes(16)), LIMIT);

        assertThat(budget.perSession()).isEqualTo(512 * 1024 + 64 * 1024 + 256 * 1024 + 64 * 1024);
        assertThat(budget.sessions()).isEqualTo(200);
        assertThat(budget.handshakes()).isEqualTo(64);
        assertThat(budget.parked()).isEqualTo(16 * MIB);
        assertThat(budget.total()).isEqualTo(200 * budget.perSession() + 64 * 64 * 1024 + 16 * MIB);
        assertThat(budget.share()).isBetween(0.75, 0.77);
        assertThat(budget.exceedsWarnShare()).isFalse();
        assertThat(budget.describe())
                .isEqualTo("200 sessions × 896 KiB + 64 handshakes × 64 KiB + 16 MiB parked = 195 MiB of 256 MiB"
                        + " (76%)");
    }

    @Test
    void parkingOffAddsNothing() {
        DirectMemoryBudget budget =
                DirectMemoryBudget.of(edge(DataSize.ofKilobytes(512)), http(DataSize.ofBytes(0)), LIMIT);

        assertThat(budget.total()).isEqualTo(200 * budget.perSession() + 64 * 64 * 1024);
        assertThat(budget.describe()).contains("+ 0 MiB parked = 179 MiB");
    }

    @Test
    void warnsAboveNinetyPercentOfTheLimit() {
        DirectMemoryBudget defaults =
                DirectMemoryBudget.of(edge(DataSize.ofKilobytes(512)), http(DataSize.ofMegabytes(16)), LIMIT);
        long justUnder = (long) Math.ceil(defaults.total() / DirectMemoryBudget.WARN_SHARE);

        assertThat(DirectMemoryBudget.of(edge(DataSize.ofKilobytes(512)), http(DataSize.ofMegabytes(16)), justUnder)
                        .exceedsWarnShare())
                .isFalse();
        assertThat(DirectMemoryBudget.of(edge(DataSize.ofKilobytes(512)), http(DataSize.ofMegabytes(16)), justUnder - 1)
                        .exceedsWarnShare())
                .isTrue();
    }

    @Test
    void aLargeSessionWindowExceedsTheLimit() {
        DirectMemoryBudget budget =
                DirectMemoryBudget.of(edge(DataSize.ofMegabytes(64)), http(DataSize.ofMegabytes(16)), LIMIT);

        assertThat(budget.exceedsWarnShare()).isTrue();
        assertThat(budget.share()).isGreaterThan(1);
        assertThat(budget.describe()).startsWith("200 sessions × 65920 KiB").endsWith("%)");
    }

    @Test
    void theLimitIsPositive() {
        assertThatIllegalArgumentException().isThrownBy(() -> new DirectMemoryBudget(1, 1, 1, 0, 0));
        assertThatIllegalArgumentException().isThrownBy(() -> new DirectMemoryBudget(1, 1, 1, -1, 1));
    }

    private static JBurrowProperties.Http http(DataSize maxParked) {
        return new JBurrowProperties.Http(
                "example.dev",
                "https",
                443,
                List.of(),
                Duration.ofSeconds(60),
                DataSize.ofMegabytes(100),
                Duration.ofSeconds(60),
                Duration.ofSeconds(75),
                DataSize.ofKilobytes(16),
                Duration.ofSeconds(1),
                maxParked,
                maxParked.toBytes() == 0 ? DataSize.ofBytes(0) : DataSize.ofMegabytes(2));
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
                4_000);
    }
}
