package io.jburrow.server.core.routing.application;

import static org.assertj.core.api.Assertions.assertThat;

import io.jburrow.protocol.control.SubdomainName;
import io.jburrow.server.core.routing.application.port.ClaimRefused;
import io.jburrow.server.core.routing.domain.Blocklist;
import io.jburrow.server.core.routing.domain.NameGenerator;
import java.time.Duration;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Timeout;

class SubdomainRoutesConcurrencyTest {

    private static final int CLAIMANTS = 64;
    private static final SubdomainName DEMO = new SubdomainName("demo");

    @RepeatedTest(20)
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void exactlyOneOfManyConcurrentClaimsWins() throws Exception {
        SubdomainRoutes<String, Integer> routes = new SubdomainRoutes<>(
                Blocklist.defaults(Set.of()),
                new NameGenerator(new Random(1)),
                Duration.ofSeconds(60),
                InstantSource.system());
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> outcomes = new ArrayList<>();

        try (ExecutorService executor = Executors.newFixedThreadPool(CLAIMANTS)) {
            for (int i = 0; i < CLAIMANTS; i++) {
                int tunnel = i;
                outcomes.add(executor.submit(() -> {
                    start.await();
                    try {
                        routes.claim(DEMO, "principal-" + tunnel, tunnel);
                        return true;
                    } catch (ClaimRefused refused) {
                        return false;
                    }
                }));
            }
            start.countDown();

            List<Integer> winners = new ArrayList<>();
            for (int i = 0; i < CLAIMANTS; i++) {
                if (outcomes.get(i).get()) {
                    winners.add(i);
                }
            }

            assertThat(winners).hasSize(1);
            assertThat(routes.find(DEMO)).contains(winners.getFirst());
        }
    }
}
