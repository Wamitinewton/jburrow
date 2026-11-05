package io.jburrow.server.core.tunnel.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.jburrow.protocol.control.Register;
import io.jburrow.protocol.control.ResumeToken;
import io.jburrow.protocol.control.SubdomainName;
import io.jburrow.protocol.control.TunnelKind;
import io.jburrow.protocol.control.TunnelRequest;
import io.jburrow.protocol.wire.Capability;
import io.jburrow.protocol.wire.ErrorCode;
import io.jburrow.protocol.wire.ProtocolException;
import io.jburrow.server.core.portpool.application.PortReservationService;
import io.jburrow.server.core.portpool.application.port.PortPoolSnapshot;
import io.jburrow.server.core.portpool.application.port.PortReservations;
import io.jburrow.server.core.portpool.application.port.Reservation;
import io.jburrow.server.core.portpool.domain.MutableClock;
import io.jburrow.server.core.portpool.domain.PortRange;
import io.jburrow.server.core.routing.application.SubdomainRoutes;
import io.jburrow.server.core.tunnel.application.port.LiveSessions;
import io.jburrow.server.core.tunnel.domain.Principal;
import io.jburrow.server.core.tunnel.domain.ResumeTokenHash;
import io.jburrow.server.core.tunnel.domain.SessionId;
import io.jburrow.server.core.tunnel.domain.Tunnel;
import io.jburrow.server.core.tunnel.domain.TunnelId;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;

class RegisterTunnelsTest {

    private static final String ALICE_TOKEN = "jb_alice";
    private static final String BOB_TOKEN = "jb_bob";
    private static final Principal ALICE = new Principal("alice");
    private static final Set<Capability> HTTP = Set.of(Capability.HTTP);
    private static final Set<Capability> RESUME = Set.of(Capability.HTTP, Capability.RESUME);
    private static final SessionId SESSION = SessionId.random();
    private static final Duration REPLACE_TIMEOUT = Duration.ofSeconds(3);
    private static final ResumeToken STALE_TOKEN = ResumeToken.generate(new Random(11));
    private static final SubdomainName DEMO = new SubdomainName("demo");

    private final InMemoryAuthtokenVerifier verifier =
            new InMemoryAuthtokenVerifier().with(ALICE_TOKEN, "alice").with(BOB_TOKEN, "bob");
    private final SessionAdmission admission = new SessionAdmission(10, 10, 1);
    private final MutableClock clock = new MutableClock();
    private final PortReservationService<Principal> ports =
            RegistrationFixtures.ports(new PortRange(21_000, 21_002), new Random(3), clock);
    private final SubdomainRoutes<Principal, TunnelId> routes =
            RegistrationFixtures.routes(RegistrationFixtures.RESUME_WINDOW, clock);
    private final RecordingTunnelMetrics metrics = new RecordingTunnelMetrics();
    private final RegisterTunnels registerTunnels = new RegisterTunnels(
            verifier, admission, ports, routes, RegistrationFixtures.policy(2, "localhost"), metrics);

    @Test
    void registersTheTunnelsInRequestOrder() {
        HandshakeTicket ticket = ticket();

        Registration registration = registerTunnels.execute(
                SESSION, ticket, register(ALICE_TOKEN, tcp("ssh", 21_002), tcp("db", null)), HTTP);

        assertThat(registration.principal()).isEqualTo(ALICE);
        assertThat(registration.tunnels())
                .extracting(planned -> planned.request().ref())
                .containsExactly("ssh", "db");
        assertThat(port(registration, 0)).isEqualTo(21_002);
        assertThat(port(registration, 1)).isBetween(21_000, 21_001);
        assertThat(registration.tunnels().getFirst().id())
                .isNotEqualTo(registration.tunnels().get(1).id());
        assertThat(admission.sessionsOf(ALICE)).isEqualTo(1);
        assertThat(admission.handshaking()).isZero();
        assertThat(ports.snapshot().reserved()).isEqualTo(2);
    }

    @Test
    void aPlannedTunnelBecomesATunnelOnThePublicHost() {
        Registration registration =
                registerTunnels.execute(SESSION, ticket(), register(ALICE_TOKEN, tcp("ssh", 21_001)), HTTP);
        PlannedTunnel planned = registration.tunnels().getFirst();

        Tunnel tunnel = planned.toTunnel(registerTunnels.policy());

        assertThat(tunnel.toRegistered().url()).isEqualTo("tcp://localhost:21001");
        assertThat(tunnel.id()).isEqualTo(planned.id());
        assertThat(tunnel.ref()).isEqualTo("ssh");
    }

    @Test
    void claimsARequestedNameForAnHttpTunnel() {
        Registration registration =
                registerTunnels.execute(SESSION, ticket(), register(ALICE_TOKEN, http("web", "demo")), HTTP);

        PlannedTunnel.Http planned = (PlannedTunnel.Http) registration.tunnels().getFirst();
        assertThat(planned.name()).isEqualTo(DEMO);
        assertThat(routes.find(DEMO)).contains(planned.id());
        assertThat(planned.toTunnel(registerTunnels.policy()).toRegistered().url())
                .isEqualTo("http://demo.localhost:7080");
        assertThat(ports.snapshot().reserved()).isZero();
        assertThat(metrics.events()).containsExactly("subdomainClaimed REQUESTED");
    }

    @Test
    void claimsARandomNameForAnHttpTunnelWithoutOne() {
        Registration registration =
                registerTunnels.execute(SESSION, ticket(), register(ALICE_TOKEN, http("web", null)), HTTP);

        PlannedTunnel.Http planned = (PlannedTunnel.Http) registration.tunnels().getFirst();
        assertThat(planned.name().value()).matches("[a-z]+-[a-z]+-[0-9a-f]{4}");
        assertThat(routes.find(planned.name())).contains(planned.id());
        assertThat(metrics.events()).containsExactly("subdomainClaimed RANDOM");
    }

    @Test
    void registersTcpAndHttpTogetherInRequestOrder() {
        Registration registration = registerTunnels.execute(
                SESSION, ticket(), register(ALICE_TOKEN, http("web", "demo"), tcp("ssh", null)), HTTP);

        assertThat(registration.tunnels())
                .extracting(planned -> planned.request().ref())
                .containsExactly("web", "ssh");
        assertThat(registration.tunnels().getFirst()).isInstanceOf(PlannedTunnel.Http.class);
        assertThat(registration.tunnels().get(1)).isInstanceOf(PlannedTunnel.Tcp.class);
    }

    @Test
    void refusesAnHttpTunnelInASessionWithoutHttpBeforeCheckingTheToken() {
        ProtocolException refused = assertRefused(
                () -> registerTunnels.execute(
                        SESSION, ticket(), register(ALICE_TOKEN, tcp("ssh", null), http("web", "demo")), Set.of()),
                ErrorCode.VALIDATION_FAILED);

        assertThat(refused.getMessage()).isEqualTo("tunnels[1].kind must be one of [tcp]");
        assertThat(verifier.calls()).isZero();
        assertThat(routes.find(DEMO)).isEmpty();
    }

    @Test
    void anUnnegotiatedKindIsAFieldRuleSoItFailsBeforeTheTunnelCount() {
        assertRefused(
                () -> registerTunnels.execute(
                        SESSION,
                        ticket(),
                        register(ALICE_TOKEN, tcp("a", null), tcp("b", null), http("web", null)),
                        Set.of()),
                ErrorCode.VALIDATION_FAILED);
    }

    @Test
    void refusesANameSomeoneElseHasNamingTheTunnel() {
        registerTunnels.execute(SESSION, ticket(), register(BOB_TOKEN, http("web", "demo")), HTTP);

        ProtocolException refused = assertRefused(
                () -> registerTunnels.execute(SESSION, ticket(), register(ALICE_TOKEN, http("site", "demo")), HTTP),
                ErrorCode.SUBDOMAIN_UNAVAILABLE);

        assertThat(refused.ref()).contains("site");
        assertThat(metrics.events()).containsExactly("subdomainClaimed REQUESTED", "subdomainClaimed UNAVAILABLE");
    }

    @Test
    void refusesAReservedOrBlockedNameNamingTheTunnel() {
        ProtocolException reserved = assertRefused(
                () -> registerTunnels.execute(SESSION, ticket(), register(ALICE_TOKEN, http("web", "admin")), HTTP),
                ErrorCode.SUBDOMAIN_FORBIDDEN);
        ProtocolException blocked = assertRefused(
                () -> registerTunnels.execute(SESSION, ticket(), register(BOB_TOKEN, http("web", "paypal-help")), HTTP),
                ErrorCode.SUBDOMAIN_FORBIDDEN);

        assertThat(reserved.ref()).contains("web");
        assertThat(blocked.ref()).contains("web");
        assertThat(metrics.events()).containsExactly("subdomainClaimed FORBIDDEN", "subdomainClaimed FORBIDDEN");
    }

    @Test
    void aRefusedNameReleasesThePortsReservedBeforeIt() {
        registerTunnels.execute(SESSION, ticket(), register(BOB_TOKEN, http("web", "demo")), HTTP);

        assertRefused(
                () -> registerTunnels.execute(
                        SESSION, ticket(), register(ALICE_TOKEN, tcp("ssh", 21_000), http("site", "demo")), HTTP),
                ErrorCode.SUBDOMAIN_UNAVAILABLE);

        assertThat(ports.snapshot()).isEqualTo(new PortPoolSnapshot(2, 0, 1, 0));
    }

    @Test
    void aRefusedPortReleasesNothingItNeverClaimed() {
        assertRefused(
                () -> registerTunnels.execute(
                        SESSION, ticket(), register(ALICE_TOKEN, http("web", "demo"), tcp("ssh", 20_000)), HTTP),
                ErrorCode.PORT_UNAVAILABLE);

        assertThat(routes.find(DEMO)).isEmpty();
        assertThat(registerTunnels
                        .execute(SESSION, ticket(), register(BOB_TOKEN, http("web", "demo")), HTTP)
                        .tunnels())
                .hasSize(1);
    }

    @Test
    void aRefusedNameReleasesTheNamesClaimedBeforeIt() {
        registerTunnels.execute(SESSION, ticket(), register(BOB_TOKEN, http("web", "taken")), HTTP);

        assertRefused(
                () -> registerTunnels.execute(
                        SESSION, ticket(), register(ALICE_TOKEN, http("first", "demo"), http("second", "taken")), HTTP),
                ErrorCode.SUBDOMAIN_UNAVAILABLE);

        assertThat(routes.find(DEMO)).isEmpty();
    }

    @Test
    void releaseHoldsPortsAndNamesForTheirPrincipal() {
        Registration registration = registerTunnels.execute(
                SESSION, ticket(), register(ALICE_TOKEN, http("web", "demo"), tcp("ssh", null)), HTTP);

        registerTunnels.release(registration);

        assertThat(ports.snapshot()).isEqualTo(new PortPoolSnapshot(2, 0, 1, 0));
        assertThat(routes.find(DEMO)).isEmpty();
        assertRefused(
                () -> registerTunnels.execute(SESSION, ticket(), register(BOB_TOKEN, http("web", "demo")), HTTP),
                ErrorCode.SUBDOMAIN_UNAVAILABLE);
    }

    @Test
    void refusesMoreTunnelsThanThePolicyAllowsBeforeCheckingTheToken() {
        HandshakeTicket ticket = ticket();

        assertRefused(
                () -> registerTunnels.execute(
                        SESSION, ticket, register("jb_unknown", tcp("a", null), tcp("b", null), tcp("c", null)), HTTP),
                ErrorCode.TUNNEL_LIMIT_EXCEEDED);

        assertThat(verifier.calls()).isZero();
        assertThat(admission.handshaking()).isEqualTo(1);
    }

    @Test
    void refusesAnUnknownAuthtoken() {
        assertRefused(
                () -> registerTunnels.execute(SESSION, ticket(), register("jb_unknown", tcp("a", null)), HTTP),
                ErrorCode.AUTHTOKEN_INVALID);

        assertThat(admission.handshaking()).isEqualTo(1);
        assertThat(ports.snapshot().reserved()).isZero();
    }

    @Test
    void refusesAPrincipalAtItsSessionCapBeforeReservingPortsOrClaimingNames() {
        registerTunnels.execute(SESSION, ticket(), register(ALICE_TOKEN, tcp("a", null)), HTTP);

        assertRefused(
                () -> registerTunnels.execute(
                        SESSION, ticket(), register(ALICE_TOKEN, tcp("a", null), http("web", "demo")), HTTP),
                ErrorCode.SESSION_LIMIT_EXCEEDED);

        assertThat(ports.snapshot().reserved()).isEqualTo(1);
        assertThat(routes.find(DEMO)).isEmpty();
    }

    @Test
    void refusesARequestedPortOutsideTheRangeNamingTheTunnel() {
        ProtocolException refused = assertRefused(
                () -> registerTunnels.execute(SESSION, ticket(), register(ALICE_TOKEN, tcp("ssh", 20_417)), HTTP),
                ErrorCode.PORT_UNAVAILABLE);

        assertThat(refused.ref()).contains("ssh");
    }

    @Test
    void refusesARequestedPortInUse() {
        ports.reserve(OptionalInt.of(21_000), ALICE);

        ProtocolException refused = assertRefused(
                () -> registerTunnels.execute(SESSION, ticket(), register(ALICE_TOKEN, tcp("ssh", 21_000)), HTTP),
                ErrorCode.PORT_UNAVAILABLE);

        assertThat(refused.ref()).contains("ssh");
    }

    @Test
    void refusesWhenThePoolIsExhausted() {
        for (int i = 0; i < 3; i++) {
            ports.reserve(OptionalInt.empty(), ALICE);
        }

        assertRefused(
                () -> registerTunnels.execute(SESSION, ticket(), register(ALICE_TOKEN, tcp("ssh", null)), HTTP),
                ErrorCode.PORT_POOL_EXHAUSTED);
    }

    @Test
    void aFailureOnTheSecondTunnelReleasesTheFirstPort() {
        ProtocolException refused = assertRefused(
                () -> registerTunnels.execute(
                        SESSION, ticket(), register(ALICE_TOKEN, tcp("first", 21_001), tcp("second", 20_000)), HTTP),
                ErrorCode.PORT_UNAVAILABLE);

        assertThat(refused.ref()).contains("second");
        assertThat(ports.snapshot()).isEqualTo(new PortPoolSnapshot(2, 0, 1, 0));
    }

    @Test
    void anUnexpectedFailureAlsoReleasesWhatWasReserved() {
        PortReservations<Principal> failingSecond = new PortReservations<>() {
            private final AtomicInteger calls = new AtomicInteger();

            @Override
            public Reservation reserve(OptionalInt requestedPort, Principal principal) {
                if (calls.incrementAndGet() == 2) {
                    throw new IllegalStateException("lease store unavailable");
                }
                return ports.reserve(requestedPort, principal);
            }

            @Override
            public void release(Reservation reservation) {
                ports.release(reservation);
            }

            @Override
            public void quarantine(Reservation reservation) {
                ports.quarantine(reservation);
            }

            @Override
            public PortPoolSnapshot snapshot() {
                return ports.snapshot();
            }
        };
        RegisterTunnels registering = new RegisterTunnels(
                verifier, admission, failingSecond, routes, RegistrationFixtures.policy(2, "localhost"), metrics);

        assertThatThrownBy(() -> registering.execute(
                        SESSION, ticket(), register(ALICE_TOKEN, tcp("first", null), tcp("second", null)), HTTP))
                .isInstanceOf(IllegalStateException.class);

        assertThat(ports.snapshot().reserved()).isZero();
    }

    @Test
    void leavesTheAdmissionToTheTicketsReleaseWhenALaterStepFails() {
        HandshakeTicket ticket = ticket();

        assertRefused(
                () -> registerTunnels.execute(SESSION, ticket, register(ALICE_TOKEN, tcp("ssh", 20_000)), HTTP),
                ErrorCode.PORT_UNAVAILABLE);
        assertThat(admission.sessionsOf(ALICE)).isEqualTo(1);

        admission.release(ticket);
        assertThat(admission.sessionsOf(ALICE)).isZero();
        assertThat(admission.activeSessions()).isZero();
    }

    @Test
    void releaseHoldsEveryPort() {
        Registration registration =
                registerTunnels.execute(SESSION, ticket(), register(ALICE_TOKEN, tcp("a", null), tcp("b", null)), HTTP);

        registerTunnels.release(registration);

        assertThat(ports.snapshot()).isEqualTo(new PortPoolSnapshot(1, 0, 2, 0));
    }

    @Test
    void aTcpTunnelsPortIsHeldForItsPrincipalAndReclaimedByIt() {
        HandshakeTicket aliceTicket = ticket();
        Registration first =
                registerTunnels.execute(SESSION, aliceTicket, register(ALICE_TOKEN, tcp("ssh", null)), HTTP);
        int port = port(first, 0);
        registerTunnels.release(first);
        admission.release(aliceTicket);

        HandshakeTicket bobTicket = ticket();
        assertRefused(
                () -> registerTunnels.execute(SESSION, bobTicket, register(BOB_TOKEN, tcp("ssh", port)), HTTP),
                ErrorCode.PORT_UNAVAILABLE);
        admission.release(bobTicket);
        Registration others =
                registerTunnels.execute(SESSION, ticket(), register(BOB_TOKEN, tcp("a", null), tcp("b", null)), HTTP);
        assertThat(List.of(port(others, 0), port(others, 1))).doesNotContain(port);

        Registration again = registerTunnels.execute(SESSION, ticket(), register(ALICE_TOKEN, tcp("ssh", port)), HTTP);

        assertThat(port(again, 0)).isEqualTo(port);
        assertThat(metrics.events())
                .filteredOn(event -> event.startsWith("portClaimed"))
                .containsExactly(
                        "portClaimed RANDOM",
                        "portClaimed UNAVAILABLE",
                        "portClaimed RANDOM",
                        "portClaimed RANDOM",
                        "portClaimed RECLAIMED");
    }

    @Test
    void aRequestedPortIsCountedAsRequestedAndOneOutsideTheRangeAsUnavailable() {
        registerTunnels.execute(SESSION, ticket(), register(ALICE_TOKEN, tcp("ssh", 21_001)), HTTP);
        assertRefused(
                () -> registerTunnels.execute(SESSION, ticket(), register(BOB_TOKEN, tcp("ssh", 20_417)), HTTP),
                ErrorCode.PORT_UNAVAILABLE);

        assertThat(metrics.events()).containsExactly("portClaimed REQUESTED", "portClaimed UNAVAILABLE");
    }

    @Test
    void anExhaustedPoolIsNotAPortClaimRefusal() {
        for (int i = 0; i < 3; i++) {
            ports.reserve(OptionalInt.empty(), ALICE);
        }

        assertRefused(
                () -> registerTunnels.execute(SESSION, ticket(), register(ALICE_TOKEN, tcp("ssh", null)), HTTP),
                ErrorCode.PORT_POOL_EXHAUSTED);

        assertThat(metrics.events()).noneMatch(event -> event.startsWith("portClaimed"));
    }

    @Test
    void aNameHeldForItsPrincipalIsCountedAsReclaimed() {
        HandshakeTicket aliceTicket = ticket();
        Registration first =
                registerTunnels.execute(SESSION, aliceTicket, register(ALICE_TOKEN, http("web", "demo")), HTTP);
        registerTunnels.release(first);
        admission.release(aliceTicket);

        registerTunnels.execute(SESSION, ticket(), register(ALICE_TOKEN, http("web", "demo")), HTTP);

        assertThat(metrics.events()).containsExactly("subdomainClaimed REQUESTED", "subdomainClaimed RECLAIMED");
    }

    @Test
    void aHeldPortIsAnyonesOnceTheWindowHasPassed() {
        HandshakeTicket aliceTicket = ticket();
        Registration first =
                registerTunnels.execute(SESSION, aliceTicket, register(ALICE_TOKEN, tcp("ssh", null)), HTTP);
        int port = port(first, 0);
        registerTunnels.release(first);
        admission.release(aliceTicket);
        clock.advance(RegistrationFixtures.RESUME_WINDOW);

        Registration bobs = registerTunnels.execute(SESSION, ticket(), register(BOB_TOKEN, tcp("ssh", port)), HTTP);

        assertThat(port(bobs, 0)).isEqualTo(port);
    }

    @Test
    void aConnectionThatClosedDuringVerificationIsNotCounted() {
        HandshakeTicket ticket = ticket();
        RegisterTunnels registering = new RegisterTunnels(
                authtoken -> {
                    admission.release(ticket);
                    return verifier.verify(authtoken);
                },
                admission,
                ports,
                routes,
                RegistrationFixtures.policy(2, "localhost"),
                metrics);

        Registration registration = registering.execute(SESSION, ticket, register(ALICE_TOKEN, tcp("a", null)), HTTP);
        registering.release(registration);

        assertThat(admission.sessionsOf(ALICE)).isZero();
        assertThat(admission.activeSessions()).isZero();
        assertThat(ports.snapshot().reserved()).isZero();
    }

    @Test
    void withResumeEveryRegistrationCarriesANewToken() {
        RegisterTunnels registering = resuming(LiveSessions.none());

        Registration first = registering.execute(SESSION, ticket(), register(BOB_TOKEN, tcp("a", null)), RESUME);
        Registration second = registering.execute(SESSION, ticket(), register(ALICE_TOKEN, tcp("b", null)), RESUME);

        ResumeTokens.Issued issued = first.resumeToken().orElseThrow();
        assertThat(issued.hash()).isEqualTo(ResumeTokenHash.of(issued.token()));
        assertThat(second.resumeToken().orElseThrow().token()).isNotEqualTo(issued.token());
        assertThat(first.withoutResumeToken().resumeToken()).isEmpty();
    }

    @Test
    void withoutResumeNoTokenIsIssuedAndAPresentedOneIsIgnored() {
        RecordingLiveSessions live = new RecordingLiveSessions(true);
        RegisterTunnels registering = resuming(live);

        Registration registration = registering.execute(SESSION, ticket(), resumeRegister(ALICE_TOKEN), HTTP);

        assertThat(registration.resumeToken()).isEmpty();
        assertThat(live.calls).isEmpty();
        assertThat(metrics.events()).noneMatch(event -> event.startsWith("resumed"));
    }

    @Test
    void stepTwoAEndsTheNamedSessionBeforeAdmission() {
        HandshakeTicket stale = ticket();
        registerTunnels.execute(SESSION, stale, register(ALICE_TOKEN, tcp("ssh", 21_001)), HTTP);
        SessionId replacement = SessionId.random();
        RecordingLiveSessions live = new RecordingLiveSessions(true) {
            @Override
            public boolean replace(ResumeTokenHash tokenHash, Principal principal, SessionId by, Duration timeout) {
                super.replace(tokenHash, principal, by, timeout);
                admission.release(stale);
                return true;
            }
        };

        Registration registration = resuming(live).execute(replacement, ticket(), resumeRegister(ALICE_TOKEN), RESUME);

        assertThat(live.calls)
                .containsExactly(new Call(ResumeTokenHash.of(STALE_TOKEN), ALICE, replacement, REPLACE_TIMEOUT, 1));
        assertThat(registration.principal()).isEqualTo(ALICE);
        assertThat(admission.sessionsOf(ALICE)).isEqualTo(1);
        assertThat(metrics.events()).contains("resumed REPLACED");
    }

    @Test
    void aTokenThatNamesNoSessionOfThePrincipalIsNotAnError() {
        HandshakeTicket other = ticket();
        registerTunnels.execute(SESSION, other, register(ALICE_TOKEN, tcp("ssh", null)), HTTP);
        RecordingLiveSessions live = new RecordingLiveSessions(false);

        assertRefused(
                () -> resuming(live).execute(SESSION, ticket(), resumeRegister(ALICE_TOKEN), RESUME),
                ErrorCode.SESSION_LIMIT_EXCEEDED);

        assertThat(live.calls).hasSize(1);
        assertThat(metrics.events()).contains("resumed NONE");
    }

    @Test
    void theAuthtokenIsVerifiedBeforeATokenIsLookedUp() {
        RecordingLiveSessions live = new RecordingLiveSessions(true);

        assertRefused(
                () -> resuming(live).execute(SESSION, ticket(), resumeRegister("jb_unknown"), RESUME),
                ErrorCode.AUTHTOKEN_INVALID);

        assertThat(live.calls).isEmpty();
    }

    private record Call(
            ResumeTokenHash tokenHash,
            Principal principal,
            SessionId replacement,
            Duration timeout,
            int sessionsOwned) {}

    /** Records each call with how many sessions the principal held when it was made. */
    private class RecordingLiveSessions implements LiveSessions {

        final List<Call> calls = new ArrayList<>();
        private final boolean replaced;

        RecordingLiveSessions(boolean replaced) {
            this.replaced = replaced;
        }

        @Override
        public boolean replace(
                ResumeTokenHash tokenHash, Principal principal, SessionId replacement, Duration timeout) {
            calls.add(new Call(tokenHash, principal, replacement, timeout, admission.sessionsOf(principal)));
            return replaced;
        }
    }

    private RegisterTunnels resuming(LiveSessions live) {
        return new RegisterTunnels(
                verifier,
                admission,
                ports,
                routes,
                RegistrationFixtures.policy(2, "localhost"),
                metrics,
                live,
                new ResumeTokens(),
                REPLACE_TIMEOUT);
    }

    private static Register resumeRegister(String authtoken) {
        return new Register(authtoken, List.of(tcp("ssh", null)), STALE_TOKEN);
    }

    private HandshakeTicket ticket() {
        return admission.tryBeginHandshake().orElseThrow();
    }

    private static int port(Registration registration, int index) {
        return ((PlannedTunnel.Tcp) registration.tunnels().get(index))
                .reservation()
                .port();
    }

    private static Register register(String authtoken, TunnelRequest... tunnels) {
        return new Register(authtoken, List.of(tunnels));
    }

    private static TunnelRequest tcp(String ref, Integer remotePort) {
        return new TunnelRequest(ref, TunnelKind.TCP, remotePort);
    }

    private static TunnelRequest http(String ref, String subdomain) {
        return new TunnelRequest(ref, TunnelKind.HTTP, subdomain, null);
    }

    private static ProtocolException assertRefused(ThrowingCallable registration, ErrorCode code) {
        Throwable thrown = catchThrowable(registration);
        assertThat(thrown).isInstanceOf(ProtocolException.class);
        ProtocolException refused = (ProtocolException) thrown;
        assertThat(refused.code()).isEqualTo(code);
        return refused;
    }
}
