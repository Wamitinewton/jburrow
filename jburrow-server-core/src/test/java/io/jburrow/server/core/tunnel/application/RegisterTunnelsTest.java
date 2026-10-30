package io.jburrow.server.core.tunnel.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.jburrow.protocol.control.Register;
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
import io.jburrow.server.core.portpool.domain.PortPool;
import io.jburrow.server.core.portpool.domain.PortRange;
import io.jburrow.server.core.routing.application.SubdomainRoutes;
import io.jburrow.server.core.tunnel.domain.Principal;
import io.jburrow.server.core.tunnel.domain.Tunnel;
import io.jburrow.server.core.tunnel.domain.TunnelId;
import java.time.Duration;
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
    private static final SubdomainName DEMO = new SubdomainName("demo");

    private final InMemoryAuthtokenVerifier verifier =
            new InMemoryAuthtokenVerifier().with(ALICE_TOKEN, "alice").with(BOB_TOKEN, "bob");
    private final SessionAdmission admission = new SessionAdmission(10, 10, 1);
    private final MutableClock clock = new MutableClock();
    private final PortReservationService ports = new PortReservationService(new PortPool(
            new PortRange(21_000, 21_002), Duration.ofSeconds(60), Duration.ofMinutes(10), new Random(3), clock));
    private final SubdomainRoutes<Principal, TunnelId> routes =
            RegistrationFixtures.routes(RegistrationFixtures.NAME_HOLD, clock);
    private final RecordingTunnelMetrics metrics = new RecordingTunnelMetrics();
    private final RegisterTunnels registerTunnels = new RegisterTunnels(
            verifier, admission, ports, routes, RegistrationFixtures.policy(2, "localhost"), metrics);

    @Test
    void registersTheTunnelsInRequestOrder() {
        HandshakeTicket ticket = ticket();

        Registration registration =
                registerTunnels.execute(ticket, register(ALICE_TOKEN, tcp("ssh", 21_002), tcp("db", null)), HTTP);

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
        Registration registration = registerTunnels.execute(ticket(), register(ALICE_TOKEN, tcp("ssh", 21_001)), HTTP);
        PlannedTunnel planned = registration.tunnels().getFirst();

        Tunnel tunnel = planned.toTunnel(registerTunnels.policy());

        assertThat(tunnel.toRegistered().url()).isEqualTo("tcp://localhost:21001");
        assertThat(tunnel.id()).isEqualTo(planned.id());
        assertThat(tunnel.ref()).isEqualTo("ssh");
    }

    @Test
    void claimsARequestedNameForAnHttpTunnel() {
        Registration registration = registerTunnels.execute(ticket(), register(ALICE_TOKEN, http("web", "demo")), HTTP);

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
        Registration registration = registerTunnels.execute(ticket(), register(ALICE_TOKEN, http("web", null)), HTTP);

        PlannedTunnel.Http planned = (PlannedTunnel.Http) registration.tunnels().getFirst();
        assertThat(planned.name().value()).matches("[a-z]+-[a-z]+-[0-9a-f]{4}");
        assertThat(routes.find(planned.name())).contains(planned.id());
        assertThat(metrics.events()).containsExactly("subdomainClaimed RANDOM");
    }

    @Test
    void registersTcpAndHttpTogetherInRequestOrder() {
        Registration registration =
                registerTunnels.execute(ticket(), register(ALICE_TOKEN, http("web", "demo"), tcp("ssh", null)), HTTP);

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
                        ticket(), register(ALICE_TOKEN, tcp("ssh", null), http("web", "demo")), Set.of()),
                ErrorCode.VALIDATION_FAILED);

        assertThat(refused.getMessage()).isEqualTo("tunnels[1].kind must be one of [tcp]");
        assertThat(verifier.calls()).isZero();
        assertThat(routes.find(DEMO)).isEmpty();
    }

    @Test
    void anUnnegotiatedKindIsAFieldRuleSoItFailsBeforeTheTunnelCount() {
        assertRefused(
                () -> registerTunnels.execute(
                        ticket(), register(ALICE_TOKEN, tcp("a", null), tcp("b", null), http("web", null)), Set.of()),
                ErrorCode.VALIDATION_FAILED);
    }

    @Test
    void refusesANameSomeoneElseHasNamingTheTunnel() {
        registerTunnels.execute(ticket(), register(BOB_TOKEN, http("web", "demo")), HTTP);

        ProtocolException refused = assertRefused(
                () -> registerTunnels.execute(ticket(), register(ALICE_TOKEN, http("site", "demo")), HTTP),
                ErrorCode.SUBDOMAIN_UNAVAILABLE);

        assertThat(refused.ref()).contains("site");
        assertThat(metrics.events()).containsExactly("subdomainClaimed REQUESTED", "subdomainClaimed UNAVAILABLE");
    }

    @Test
    void refusesAReservedOrBlockedNameNamingTheTunnel() {
        ProtocolException reserved = assertRefused(
                () -> registerTunnels.execute(ticket(), register(ALICE_TOKEN, http("web", "admin")), HTTP),
                ErrorCode.SUBDOMAIN_FORBIDDEN);
        ProtocolException blocked = assertRefused(
                () -> registerTunnels.execute(ticket(), register(BOB_TOKEN, http("web", "paypal-help")), HTTP),
                ErrorCode.SUBDOMAIN_FORBIDDEN);

        assertThat(reserved.ref()).contains("web");
        assertThat(blocked.ref()).contains("web");
        assertThat(metrics.events()).containsExactly("subdomainClaimed FORBIDDEN", "subdomainClaimed FORBIDDEN");
    }

    @Test
    void aRefusedNameReleasesThePortsReservedBeforeIt() {
        registerTunnels.execute(ticket(), register(BOB_TOKEN, http("web", "demo")), HTTP);

        assertRefused(
                () -> registerTunnels.execute(
                        ticket(), register(ALICE_TOKEN, tcp("ssh", 21_000), http("site", "demo")), HTTP),
                ErrorCode.SUBDOMAIN_UNAVAILABLE);

        assertThat(ports.snapshot()).isEqualTo(new PortPoolSnapshot(3, 0, 0));
    }

    @Test
    void aRefusedPortReleasesNothingItNeverClaimed() {
        assertRefused(
                () -> registerTunnels.execute(
                        ticket(), register(ALICE_TOKEN, http("web", "demo"), tcp("ssh", 20_000)), HTTP),
                ErrorCode.PORT_UNAVAILABLE);

        assertThat(routes.find(DEMO)).isEmpty();
        assertThat(registerTunnels
                        .execute(ticket(), register(BOB_TOKEN, http("web", "demo")), HTTP)
                        .tunnels())
                .hasSize(1);
    }

    @Test
    void aRefusedNameReleasesTheNamesClaimedBeforeIt() {
        registerTunnels.execute(ticket(), register(BOB_TOKEN, http("web", "taken")), HTTP);

        assertRefused(
                () -> registerTunnels.execute(
                        ticket(), register(ALICE_TOKEN, http("first", "demo"), http("second", "taken")), HTTP),
                ErrorCode.SUBDOMAIN_UNAVAILABLE);

        assertThat(routes.find(DEMO)).isEmpty();
    }

    @Test
    void releaseReturnsPortsAndHoldsNamesForTheirPrincipal() {
        Registration registration =
                registerTunnels.execute(ticket(), register(ALICE_TOKEN, http("web", "demo"), tcp("ssh", null)), HTTP);

        registerTunnels.release(registration);

        assertThat(ports.snapshot()).isEqualTo(new PortPoolSnapshot(3, 0, 0));
        assertThat(routes.find(DEMO)).isEmpty();
        assertRefused(
                () -> registerTunnels.execute(ticket(), register(BOB_TOKEN, http("web", "demo")), HTTP),
                ErrorCode.SUBDOMAIN_UNAVAILABLE);
    }

    @Test
    void refusesMoreTunnelsThanThePolicyAllowsBeforeCheckingTheToken() {
        HandshakeTicket ticket = ticket();

        assertRefused(
                () -> registerTunnels.execute(
                        ticket, register("jb_unknown", tcp("a", null), tcp("b", null), tcp("c", null)), HTTP),
                ErrorCode.TUNNEL_LIMIT_EXCEEDED);

        assertThat(verifier.calls()).isZero();
        assertThat(admission.handshaking()).isEqualTo(1);
    }

    @Test
    void refusesAnUnknownAuthtoken() {
        assertRefused(
                () -> registerTunnels.execute(ticket(), register("jb_unknown", tcp("a", null)), HTTP),
                ErrorCode.AUTHTOKEN_INVALID);

        assertThat(admission.handshaking()).isEqualTo(1);
        assertThat(ports.snapshot().reserved()).isZero();
    }

    @Test
    void refusesAPrincipalAtItsSessionCapBeforeReservingPortsOrClaimingNames() {
        registerTunnels.execute(ticket(), register(ALICE_TOKEN, tcp("a", null)), HTTP);

        assertRefused(
                () -> registerTunnels.execute(
                        ticket(), register(ALICE_TOKEN, tcp("a", null), http("web", "demo")), HTTP),
                ErrorCode.SESSION_LIMIT_EXCEEDED);

        assertThat(ports.snapshot().reserved()).isEqualTo(1);
        assertThat(routes.find(DEMO)).isEmpty();
    }

    @Test
    void refusesARequestedPortOutsideTheRangeNamingTheTunnel() {
        ProtocolException refused = assertRefused(
                () -> registerTunnels.execute(ticket(), register(ALICE_TOKEN, tcp("ssh", 20_417)), HTTP),
                ErrorCode.PORT_UNAVAILABLE);

        assertThat(refused.ref()).contains("ssh");
    }

    @Test
    void refusesARequestedPortInUse() {
        ports.reserve(OptionalInt.of(21_000));

        ProtocolException refused = assertRefused(
                () -> registerTunnels.execute(ticket(), register(ALICE_TOKEN, tcp("ssh", 21_000)), HTTP),
                ErrorCode.PORT_UNAVAILABLE);

        assertThat(refused.ref()).contains("ssh");
    }

    @Test
    void refusesWhenThePoolIsExhausted() {
        for (int i = 0; i < 3; i++) {
            ports.reserve(OptionalInt.empty());
        }

        assertRefused(
                () -> registerTunnels.execute(ticket(), register(ALICE_TOKEN, tcp("ssh", null)), HTTP),
                ErrorCode.PORT_POOL_EXHAUSTED);
    }

    @Test
    void aFailureOnTheSecondTunnelReleasesTheFirstPort() {
        ProtocolException refused = assertRefused(
                () -> registerTunnels.execute(
                        ticket(), register(ALICE_TOKEN, tcp("first", 21_001), tcp("second", 20_000)), HTTP),
                ErrorCode.PORT_UNAVAILABLE);

        assertThat(refused.ref()).contains("second");
        assertThat(ports.snapshot()).isEqualTo(new PortPoolSnapshot(3, 0, 0));
    }

    @Test
    void anUnexpectedFailureAlsoReleasesWhatWasReserved() {
        PortReservations failingSecond = new PortReservations() {
            private final AtomicInteger calls = new AtomicInteger();

            @Override
            public Reservation reserve(OptionalInt requestedPort) {
                if (calls.incrementAndGet() == 2) {
                    throw new IllegalStateException("lease store unavailable");
                }
                return ports.reserve(requestedPort);
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
                        ticket(), register(ALICE_TOKEN, tcp("first", null), tcp("second", null)), HTTP))
                .isInstanceOf(IllegalStateException.class);

        assertThat(ports.snapshot().reserved()).isZero();
    }

    @Test
    void leavesTheAdmissionToTheTicketsReleaseWhenALaterStepFails() {
        HandshakeTicket ticket = ticket();

        assertRefused(
                () -> registerTunnels.execute(ticket, register(ALICE_TOKEN, tcp("ssh", 20_000)), HTTP),
                ErrorCode.PORT_UNAVAILABLE);
        assertThat(admission.sessionsOf(ALICE)).isEqualTo(1);

        admission.release(ticket);
        assertThat(admission.sessionsOf(ALICE)).isZero();
        assertThat(admission.activeSessions()).isZero();
    }

    @Test
    void releaseReturnsEveryPort() {
        Registration registration =
                registerTunnels.execute(ticket(), register(ALICE_TOKEN, tcp("a", null), tcp("b", null)), HTTP);

        registerTunnels.release(registration);

        assertThat(ports.snapshot()).isEqualTo(new PortPoolSnapshot(3, 0, 0));
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

        Registration registration = registering.execute(ticket, register(ALICE_TOKEN, tcp("a", null)), HTTP);
        registering.release(registration);

        assertThat(admission.sessionsOf(ALICE)).isZero();
        assertThat(admission.activeSessions()).isZero();
        assertThat(ports.snapshot().reserved()).isZero();
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
