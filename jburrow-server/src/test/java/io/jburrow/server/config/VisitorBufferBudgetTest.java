package io.jburrow.server.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.Test;

/** The kernel memory line of {@code docs/phase-2-multiplexing/ARCHITECTURE.md} §Memory budget. */
class VisitorBufferBudgetTest {

    private static final long KIB = 1024;

    @Test
    void theDefaultsMatchTheArchitecture() {
        VisitorBufferBudget budget = new VisitorBufferBudget(4_000, 32 * KIB);

        assertThat(budget.kernelSized()).isFalse();
        assertThat(budget.perStream()).as("doubled by Linux").isEqualTo(64 * KIB);
        assertThat(budget.describe()).isEqualTo("4000 streams × 64 KiB = 250 MiB of kernel memory");
    }

    @Test
    void aKernelSizedBufferIsNamedAsUnbounded() {
        VisitorBufferBudget budget = new VisitorBufferBudget(4_000, 0);

        assertThat(budget.kernelSized()).isTrue();
        assertThat(budget.describe())
                .startsWith("4000 streams × the kernel's sizing")
                .contains("unbounded");
    }

    @Test
    void aBudgetIsNeverNegative() {
        assertThatIllegalArgumentException().isThrownBy(() -> new VisitorBufferBudget(-1, 0));
        assertThatIllegalArgumentException().isThrownBy(() -> new VisitorBufferBudget(1, -1));
    }
}
