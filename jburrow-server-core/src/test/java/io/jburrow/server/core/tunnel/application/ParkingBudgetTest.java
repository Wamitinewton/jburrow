package io.jburrow.server.core.tunnel.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ParkingBudgetTest {

    @Test
    void aSessionParksUpToItsOwnLimit() {
        ParkingBudget budget = new ParkingBudget(16, 4);
        ParkingBudget.Account session = budget.account();

        assertThat(session.tryTake(3)).isTrue();
        assertThat(session.tryTake(2)).isFalse();
        assertThat(session.tryTake(1)).isTrue();

        assertThat(session.held()).isEqualTo(4);
        assertThat(budget.parked()).isEqualTo(4);
    }

    @Test
    void sessionsTogetherParkUpToTheServersLimit() {
        ParkingBudget budget = new ParkingBudget(6, 4);
        ParkingBudget.Account first = budget.account();
        ParkingBudget.Account second = budget.account();
        first.tryTake(4);

        assertThat(second.tryTake(3)).isFalse();
        assertThat(second.held()).isZero();
        assertThat(second.tryTake(2)).isTrue();
        assertThat(budget.parked()).isEqualTo(6);
    }

    @Test
    void givenBackBytesCanBeTakenAgainByAnySession() {
        ParkingBudget budget = new ParkingBudget(4, 4);
        ParkingBudget.Account first = budget.account();
        ParkingBudget.Account second = budget.account();
        first.tryTake(4);

        first.giveBack(3);
        first.giveBack(0);

        assertThat(first.held()).isOne();
        assertThat(second.tryTake(3)).isTrue();
        assertThat(budget.parked()).isEqualTo(4);
    }

    @Test
    void givingBackMoreThanTakenIsABug() {
        ParkingBudget.Account session = new ParkingBudget(8, 8).account();
        session.tryTake(2);

        assertThatIllegalStateException().isThrownBy(() -> session.giveBack(3));
        assertThatIllegalStateException().isThrownBy(() -> session.giveBack(-1));
        assertThat(session.held()).isEqualTo(2);
    }

    @Test
    void aZeroBudgetTurnsParkingOff() {
        assertThat(new ParkingBudget(0, 0).enabled()).isFalse();
        assertThat(new ParkingBudget(0, 0).account().tryTake(1)).isFalse();
        assertThat(new ParkingBudget(1, 1).enabled()).isTrue();
    }

    @Test
    void limitsAreCheckedOnConstructionAndTakesAreAtLeastOneByte() {
        assertThatIllegalArgumentException().isThrownBy(() -> new ParkingBudget(-1, 0));
        assertThatIllegalArgumentException().isThrownBy(() -> new ParkingBudget(4, 5));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ParkingBudget(4, 4).account().tryTake(0));
    }

    @Test
    void theServerTotalHoldsWhenSessionsOnManyThreadsTakeAtOnce() {
        ParkingBudget budget = new ParkingBudget(1_000, 1_000);
        AtomicInteger taken = new AtomicInteger();
        List<CompletableFuture<Void>> sessions = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            ParkingBudget.Account session = budget.account();
            sessions.add(CompletableFuture.runAsync(() -> {
                for (int j = 0; j < 500; j++) {
                    if (session.tryTake(1)) {
                        taken.incrementAndGet();
                    }
                }
            }));
        }
        CompletableFuture.allOf(sessions.toArray(CompletableFuture[]::new)).join();

        assertThat(taken.get()).isEqualTo(1_000);
        assertThat(budget.parked()).isEqualTo(1_000);
    }
}
