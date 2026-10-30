package io.jburrow.server.core.routing.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.jburrow.protocol.control.SubdomainName;
import io.jburrow.server.core.portpool.domain.MutableClock;
import io.jburrow.server.core.routing.application.port.ClaimRefused;
import io.jburrow.server.core.routing.domain.Blocklist;
import io.jburrow.server.core.routing.domain.NameGenerator;
import java.time.Duration;
import java.util.Random;
import java.util.Set;
import java.util.random.RandomGenerator;
import org.junit.jupiter.api.Test;

class SubdomainRoutesTest {

    private static final Duration HOLD = Duration.ofSeconds(60);
    private static final SubdomainName DEMO = new SubdomainName("demo");

    private final MutableClock clock = new MutableClock();

    private SubdomainRoutes<String, String> routes(Duration hold) {
        return routes(hold, new Random(42));
    }

    private SubdomainRoutes<String, String> routes(Duration hold, RandomGenerator random) {
        return new SubdomainRoutes<>(Blocklist.defaults(Set.of()), new NameGenerator(random), hold, clock);
    }

    @Test
    void claimsAFreeNameAndRoutesIt() {
        SubdomainRoutes<String, String> routes = routes(HOLD);

        assertThat(routes.claim(DEMO, "alice", "t1")).isEqualTo(DEMO);

        assertThat(routes.find(DEMO)).contains("t1");
    }

    @Test
    void unclaimedNamesRouteNowhere() {
        assertThat(routes(HOLD).find(DEMO)).isEmpty();
    }

    @Test
    void refusesABlockedName() {
        SubdomainRoutes<String, String> routes = routes(HOLD);

        assertRefused(() -> routes.claim(new SubdomainName("api"), "alice", "t1"), ClaimRefused.Reason.FORBIDDEN);
        assertRefused(
                () -> routes.claim(new SubdomainName("paypal-login"), "alice", "t1"), ClaimRefused.Reason.FORBIDDEN);
        assertThat(routes.size()).isZero();
    }

    @Test
    void refusesANameAnotherTunnelHolds() {
        SubdomainRoutes<String, String> routes = routes(HOLD);
        routes.claim(DEMO, "alice", "t1");

        assertRefused(() -> routes.claim(DEMO, "bob", "t2"), ClaimRefused.Reason.UNAVAILABLE);
        assertRefused(() -> routes.claim(DEMO, "alice", "t2"), ClaimRefused.Reason.UNAVAILABLE);
        assertThat(routes.find(DEMO)).contains("t1");
    }

    @Test
    void aReleasedNameIsHeldAndRoutesNowhere() {
        SubdomainRoutes<String, String> routes = routes(HOLD);
        routes.claim(DEMO, "alice", "t1");

        routes.release(DEMO, "t1");

        assertThat(routes.find(DEMO)).isEmpty();
        assertRefused(() -> routes.claim(DEMO, "bob", "t2"), ClaimRefused.Reason.UNAVAILABLE);
    }

    @Test
    void theSamePrincipalGetsAHeldNameBack() {
        SubdomainRoutes<String, String> routes = routes(HOLD);
        routes.claim(DEMO, "alice", "t1");
        routes.release(DEMO, "t1");
        clock.advance(HOLD.minusSeconds(1));

        routes.claim(DEMO, "alice", "t2");

        assertThat(routes.find(DEMO)).contains("t2");
    }

    @Test
    void anyoneMayClaimANameOnceItsHoldExpires() {
        SubdomainRoutes<String, String> routes = routes(HOLD);
        routes.claim(DEMO, "alice", "t1");
        routes.release(DEMO, "t1");
        clock.advance(HOLD.minusMillis(1));
        assertRefused(() -> routes.claim(DEMO, "bob", "t2"), ClaimRefused.Reason.UNAVAILABLE);

        clock.advance(Duration.ofMillis(1));
        routes.claim(DEMO, "bob", "t2");

        assertThat(routes.find(DEMO)).contains("t2");
    }

    @Test
    void aZeroHoldFreesTheNameAtOnce() {
        SubdomainRoutes<String, String> routes = routes(Duration.ZERO);
        routes.claim(DEMO, "alice", "t1");

        routes.release(DEMO, "t1");

        assertThat(routes.size()).isZero();
        routes.claim(DEMO, "bob", "t2");
        assertThat(routes.find(DEMO)).contains("t2");
    }

    @Test
    void aLateReleaseOfAReplacedClaimIsIgnored() {
        SubdomainRoutes<String, String> routes = routes(HOLD);
        routes.claim(DEMO, "alice", "t1");
        routes.release(DEMO, "t1");
        routes.claim(DEMO, "alice", "t2");

        routes.release(DEMO, "t1");

        assertThat(routes.find(DEMO)).contains("t2");
    }

    @Test
    void releasingAHeldNameAgainDoesNotExtendItsHold() {
        SubdomainRoutes<String, String> routes = routes(HOLD);
        routes.claim(DEMO, "alice", "t1");
        routes.release(DEMO, "t1");
        clock.advance(HOLD.minusSeconds(1));

        routes.release(DEMO, "t1");
        clock.advance(Duration.ofSeconds(1));

        routes.claim(DEMO, "bob", "t2");
        assertThat(routes.find(DEMO)).contains("t2");
    }

    @Test
    void releasingAnUnclaimedNameDoesNothing() {
        SubdomainRoutes<String, String> routes = routes(HOLD);

        routes.release(DEMO, "t1");

        assertThat(routes.size()).isZero();
    }

    @Test
    void claimsARandomName() {
        SubdomainRoutes<String, String> routes = routes(HOLD);

        SubdomainName name = routes.claimRandom("alice", "t1");

        assertThat(name.value()).matches("[a-z]{3,7}-[a-z]{3,7}-[0-9a-f]{4}");
        assertThat(routes.find(name)).contains("t1");
    }

    @Test
    void aRandomDrawSkipsAClaimedName() {
        SubdomainName firstDraw = new NameGenerator(new Random(7)).next();
        SubdomainRoutes<String, String> routes = routes(HOLD, new Random(7));
        routes.claim(firstDraw, "bob", "t0");

        SubdomainName name = routes.claimRandom("alice", "t1");

        assertThat(name).isNotEqualTo(firstDraw);
        assertThat(routes.find(firstDraw)).contains("t0");
    }

    @Test
    void aRandomDrawSkipsAHeldNameEvenForItsPrincipal() {
        SubdomainName firstDraw = new NameGenerator(new Random(7)).next();
        SubdomainRoutes<String, String> routes = routes(HOLD, new Random(7));
        routes.claim(firstDraw, "alice", "t0");
        routes.release(firstDraw, "t0");

        assertThat(routes.claimRandom("alice", "t1")).isNotEqualTo(firstDraw);
    }

    @Test
    void aRandomDrawSkipsAnExpiredHoldNotYetSwept() {
        SubdomainName firstDraw = new NameGenerator(new Random(7)).next();
        SubdomainRoutes<String, String> routes = routes(HOLD, new Random(7));
        routes.claim(firstDraw, "bob", "t0");
        routes.release(firstDraw, "t0");
        clock.advance(HOLD.multipliedBy(2));

        assertThat(routes.claimRandom("alice", "t1")).isNotEqualTo(firstDraw);
    }

    @Test
    void givesUpAfterEightTakenDraws() {
        SubdomainRoutes<String, String> routes = routes(HOLD, () -> 0L);
        SubdomainName only = routes.claimRandom("alice", "t1");

        assertThatIllegalStateException()
                .isThrownBy(() -> routes.claimRandom("bob", "t2"))
                .withMessageContaining("8 random names");
        assertThat(routes.find(only)).contains("t1");
    }

    @Test
    void sweepsExpiredHoldsAtMostOncePerInterval() {
        assertThat(SubdomainRoutes.SWEEP_INTERVAL).isEqualTo(HOLD);
        SubdomainRoutes<String, String> routes = routes(HOLD);
        SubdomainName other = new SubdomainName("other");
        SubdomainName unclaimed = new SubdomainName("unclaimed");
        routes.claim(DEMO, "alice", "t1");
        routes.release(DEMO, "t1");
        clock.advance(Duration.ofSeconds(59));
        routes.claim(other, "bob", "t2");
        routes.release(other, "t2");
        assertThat(routes.size()).isEqualTo(2);

        clock.advance(Duration.ofSeconds(1));
        routes.release(unclaimed, "t9");
        assertThat(routes.size()).isEqualTo(1);

        clock.advance(Duration.ofSeconds(59));
        routes.release(unclaimed, "t9");
        assertThat(routes.size()).isEqualTo(1);

        clock.advance(Duration.ofSeconds(1));
        routes.release(unclaimed, "t9");
        assertThat(routes.size()).isZero();
    }

    @Test
    void refusesANegativeHold() {
        assertThatIllegalArgumentException().isThrownBy(() -> routes(Duration.ofSeconds(-1)));
    }

    private static void assertRefused(Runnable claim, ClaimRefused.Reason reason) {
        assertThatThrownBy(claim::run)
                .isInstanceOfSatisfying(
                        ClaimRefused.class,
                        refused -> assertThat(refused.reason()).isEqualTo(reason));
    }
}
