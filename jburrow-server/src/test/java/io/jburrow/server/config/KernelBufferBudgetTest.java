package io.jburrow.server.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.Test;

/** The kernel memory line of {@code docs/phase-3-http-tunnels/ARCHITECTURE.md} §Memory budget. */
class KernelBufferBudgetTest {

    private static final long KIB = 1024;

    @Test
    void theDefaultsMatchTheArchitecture() {
        KernelBufferBudget budget = new KernelBufferBudget(4_000, 32 * KIB, 16 * KIB);

        assertThat(budget.kernelSized()).isFalse();
        assertThat(budget.perStream()).as("doubled by Linux").isEqualTo(64 * KIB);
        assertThat(budget.describe())
                .isEqualTo("4000 streams × 64 KiB (a TCP visitor's receive buffer, or an exchange's send and receive"
                        + " buffers) = 250 MiB of kernel memory");
    }

    @Test
    void aStreamIsBudgetedAtTheLargerOfAVisitorsBuffersAndAnExchanges() {
        assertThat(new KernelBufferBudget(1, 32 * KIB, 64 * KIB).perStream()).isEqualTo(256 * KIB);
        assertThat(new KernelBufferBudget(1, 256 * KIB, 16 * KIB).perStream()).isEqualTo(512 * KIB);
    }

    @Test
    void aKernelSizedBufferIsNamedAsUnbounded() {
        assertThat(new KernelBufferBudget(4_000, 0, 16 * KIB).describe())
                .startsWith("4000 streams × the kernel's sizing")
                .endsWith("unbounded by jburrow.tcp.visitor-receive-buffer");
        assertThat(new KernelBufferBudget(4_000, 32 * KIB, 0).describe())
                .endsWith("unbounded by jburrow.http.socket-buffer");
        assertThat(new KernelBufferBudget(4_000, 0, 0).describe())
                .endsWith("unbounded by jburrow.tcp.visitor-receive-buffer and jburrow.http.socket-buffer");
        assertThat(new KernelBufferBudget(4_000, 32 * KIB, 0).kernelSized()).isTrue();
    }

    @Test
    void aBudgetIsNeverNegative() {
        assertThatIllegalArgumentException().isThrownBy(() -> new KernelBufferBudget(-1, 0, 0));
        assertThatIllegalArgumentException().isThrownBy(() -> new KernelBufferBudget(1, -1, 0));
        assertThatIllegalArgumentException().isThrownBy(() -> new KernelBufferBudget(1, 0, -1));
    }
}
