package io.jburrow.server.core.tunnel.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.jburrow.protocol.wire.ErrorCode;
import io.jburrow.protocol.wire.ProtocolException;
import io.jburrow.server.core.tunnel.domain.Principal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class SessionAdmissionTest {

    private static final Principal ALICE = new Principal("alice");
    private static final Principal BOB = new Principal("bob");

    @Test
    void aTicketCountsAsASessionAndAHandshake() {
        SessionAdmission admission = new SessionAdmission(10, 10, 3);

        admission.tryBeginHandshake().orElseThrow();

        assertThat(admission.activeSessions()).isEqualTo(1);
        assertThat(admission.handshaking()).isEqualTo(1);
    }

    @Test
    void refusesBeyondTheSessionCap() {
        SessionAdmission admission = new SessionAdmission(2, 10, 3);
        admission.admit(admission.tryBeginHandshake().orElseThrow(), ALICE);
        admission.tryBeginHandshake().orElseThrow();

        assertThat(admission.tryBeginHandshake()).isEmpty();
        assertThat(admission.activeSessions()).isEqualTo(2);
    }

    @Test
    void refusesBeyondTheHandshakeCap() {
        SessionAdmission admission = new SessionAdmission(10, 2, 3);
        admission.tryBeginHandshake().orElseThrow();
        HandshakeTicket second = admission.tryBeginHandshake().orElseThrow();

        assertThat(admission.tryBeginHandshake()).isEmpty();

        admission.admit(second, ALICE);
        assertThat(admission.tryBeginHandshake()).isPresent();
    }

    @Test
    void admittingFreesTheHandshakeSlotAndCountsThePrincipal() {
        SessionAdmission admission = new SessionAdmission(10, 10, 3);
        HandshakeTicket ticket = admission.tryBeginHandshake().orElseThrow();

        admission.admit(ticket, ALICE);

        assertThat(admission.activeSessions()).isEqualTo(1);
        assertThat(admission.handshaking()).isZero();
        assertThat(admission.sessionsOf(ALICE)).isEqualTo(1);
    }

    @Test
    void refusesAPrincipalAtItsCapWithoutCountingIt() {
        SessionAdmission admission = new SessionAdmission(10, 10, 1);
        admission.admit(admission.tryBeginHandshake().orElseThrow(), ALICE);
        HandshakeTicket second = admission.tryBeginHandshake().orElseThrow();

        assertThatThrownBy(() -> admission.admit(second, ALICE))
                .isInstanceOfSatisfying(
                        ProtocolException.class,
                        refused -> assertThat(refused.code()).isEqualTo(ErrorCode.SESSION_LIMIT_EXCEEDED));

        assertThat(admission.sessionsOf(ALICE)).isEqualTo(1);
        assertThat(admission.handshaking()).isEqualTo(1);
        admission.admit(second, BOB);
        assertThat(admission.sessionsOf(BOB)).isEqualTo(1);
    }

    @Test
    void releaseFreesWhatTheTicketHoldsAndIsIdempotent() {
        SessionAdmission admission = new SessionAdmission(10, 10, 3);
        HandshakeTicket handshaking = admission.tryBeginHandshake().orElseThrow();
        HandshakeTicket admitted = admission.tryBeginHandshake().orElseThrow();
        admission.admit(admitted, ALICE);

        admission.release(handshaking);
        admission.release(handshaking);
        admission.release(admitted);
        admission.release(admitted);

        assertThat(admission.activeSessions()).isZero();
        assertThat(admission.handshaking()).isZero();
        assertThat(admission.sessionsOf(ALICE)).isZero();
    }

    @Test
    void admittingAReleasedTicketDoesNothing() {
        SessionAdmission admission = new SessionAdmission(10, 10, 1);
        HandshakeTicket ticket = admission.tryBeginHandshake().orElseThrow();
        admission.release(ticket);

        admission.admit(ticket, ALICE);

        assertThat(admission.sessionsOf(ALICE)).isZero();
        assertThat(admission.activeSessions()).isZero();
        assertThat(admission.handshaking()).isZero();
    }

    @Test
    void admittingTwiceIsABug() {
        SessionAdmission admission = new SessionAdmission(10, 10, 3);
        HandshakeTicket ticket = admission.tryBeginHandshake().orElseThrow();
        admission.admit(ticket, ALICE);

        assertThatIllegalStateException().isThrownBy(() -> admission.admit(ticket, ALICE));
        assertThat(admission.sessionsOf(ALICE)).isEqualTo(1);
    }

    @Test
    void refusesAnotherAdmissionsTicket() {
        SessionAdmission admission = new SessionAdmission(10, 10, 3);
        HandshakeTicket foreign =
                new SessionAdmission(10, 10, 3).tryBeginHandshake().orElseThrow();

        assertThatIllegalArgumentException().isThrownBy(() -> admission.release(foreign));
        assertThatIllegalArgumentException().isThrownBy(() -> admission.admit(foreign, ALICE));
    }

    @Test
    void rejectsNonPositiveCaps() {
        assertThatIllegalArgumentException().isThrownBy(() -> new SessionAdmission(0, 1, 1));
        assertThatIllegalArgumentException().isThrownBy(() -> new SessionAdmission(1, 0, 1));
        assertThatIllegalArgumentException().isThrownBy(() -> new SessionAdmission(1, 1, 0));
    }

    /**
     * Sixteen threads begin, admit and release as fast as they can, sometimes releasing twice. The counters must never
     * pass their caps, and must all come back to zero.
     */
    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void staysWithinItsCapsUnderContention() throws Exception {
        int maxSessions = 20;
        int maxHandshaking = 8;
        int maxPerPrincipal = 3;
        SessionAdmission admission = new SessionAdmission(maxSessions, maxHandshaking, maxPerPrincipal);
        List<Principal> principals =
                List.of(ALICE, BOB, new Principal("carol"), new Principal("dave"), new Principal("erin"));
        AtomicInteger violations = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);

        int threads = 16;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        try {
            List<Future<?>> workers = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                workers.add(executor.submit(() -> {
                    start.await();
                    ThreadLocalRandom random = ThreadLocalRandom.current();
                    for (int i = 0; i < 20_000; i++) {
                        Optional<HandshakeTicket> ticket = admission.tryBeginHandshake();
                        checkCaps(admission, maxSessions, maxHandshaking, maxPerPrincipal, principals, violations);
                        if (ticket.isEmpty()) {
                            continue;
                        }
                        if (random.nextBoolean()) {
                            try {
                                admission.admit(ticket.get(), principals.get(random.nextInt(principals.size())));
                            } catch (ProtocolException refused) {
                                if (refused.code() != ErrorCode.SESSION_LIMIT_EXCEEDED) {
                                    violations.incrementAndGet();
                                }
                            }
                            checkCaps(admission, maxSessions, maxHandshaking, maxPerPrincipal, principals, violations);
                        }
                        admission.release(ticket.get());
                        if (random.nextInt(4) == 0) {
                            admission.release(ticket.get());
                        }
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> worker : workers) {
                worker.get();
            }
        } finally {
            executor.shutdownNow();
        }

        assertThat(violations).hasValue(0);
        assertThat(admission.activeSessions()).isZero();
        assertThat(admission.handshaking()).isZero();
        assertThat(principals)
                .allSatisfy(
                        principal -> assertThat(admission.sessionsOf(principal)).isZero());
    }

    /** A connection closing on the event loop while its registration admits it on a virtual thread. */
    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void aReleaseRacingAnAdmissionIsCountedOnce() throws Exception {
        SessionAdmission admission = new SessionAdmission(4, 4, 4);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            for (int i = 0; i < 20_000; i++) {
                HandshakeTicket ticket = admission.tryBeginHandshake().orElseThrow();
                CountDownLatch start = new CountDownLatch(1);
                Future<?> admit = executor.submit(() -> {
                    start.await();
                    admission.admit(ticket, ALICE);
                    return null;
                });
                Future<?> release = executor.submit(() -> {
                    start.await();
                    admission.release(ticket);
                    return null;
                });
                start.countDown();
                admit.get();
                release.get();

                assertThat(admission.activeSessions()).isZero();
                assertThat(admission.handshaking()).isZero();
                assertThat(admission.sessionsOf(ALICE)).isZero();
            }
        } finally {
            executor.shutdownNow();
        }
    }

    private static void checkCaps(
            SessionAdmission admission,
            int maxSessions,
            int maxHandshaking,
            int maxPerPrincipal,
            List<Principal> principals,
            AtomicInteger violations) {
        if (admission.activeSessions() > maxSessions || admission.handshaking() > maxHandshaking) {
            violations.incrementAndGet();
        }
        for (Principal principal : principals) {
            if (admission.sessionsOf(principal) > maxPerPrincipal) {
                violations.incrementAndGet();
            }
        }
    }
}
