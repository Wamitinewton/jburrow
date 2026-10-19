package io.jburrow.server.core.tunnel.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.jburrow.protocol.control.Register;
import io.jburrow.protocol.control.TunnelKind;
import io.jburrow.protocol.control.TunnelRequest;
import io.jburrow.protocol.wire.ErrorCode;
import io.jburrow.protocol.wire.ProtocolException;
import io.jburrow.server.core.portpool.application.PortReservationService;
import io.jburrow.server.core.portpool.application.port.PortPoolSnapshot;
import io.jburrow.server.core.portpool.application.port.PortReservations;
import io.jburrow.server.core.portpool.application.port.Reservation;
import io.jburrow.server.core.portpool.domain.MutableClock;
import io.jburrow.server.core.portpool.domain.PortPool;
import io.jburrow.server.core.portpool.domain.PortRange;
import io.jburrow.server.core.tunnel.domain.Principal;
import io.jburrow.server.core.tunnel.domain.Tunnel;
import java.time.Duration;
import java.util.List;
import java.util.OptionalInt;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;

class RegisterTunnelsTest {

    private static final String ALICE_TOKEN = "jb_alice";
    private static final Principal ALICE = new Principal("alice");

    private final InMemoryAuthtokenVerifier verifier = new InMemoryAuthtokenVerifier().with(ALICE_TOKEN, "alice");
    private final SessionAdmission admission = new SessionAdmission(10, 10, 1);
    private final PortReservationService ports = new PortReservationService(new PortPool(
            new PortRange(21_000, 21_002),
            Duration.ofSeconds(60),
            Duration.ofMinutes(10),
            new Random(3),
            new MutableClock()));
    private final RegisterTunnels registerTunnels =
            new RegisterTunnels(verifier, admission, ports, new RegistrationPolicy(2, "localhost"));

    @Test
    void registersTheTunnelsInRequestOrder() {
        HandshakeTicket ticket = ticket();

        Registration registration =
                registerTunnels.execute(ticket, register(ALICE_TOKEN, tunnel("ssh", 21_002), tunnel("db", null)));

        assertThat(registration.principal()).isEqualTo(ALICE);
        assertThat(registration.tunnels())
                .extracting(planned -> planned.request().ref())
                .containsExactly("ssh", "db");
        assertThat(registration.tunnels().getFirst().reservation().port()).isEqualTo(21_002);
        assertThat(registration.tunnels().get(1).reservation().port()).isBetween(21_000, 21_001);
        assertThat(registration.tunnels().getFirst().id())
                .isNotEqualTo(registration.tunnels().get(1).id());
        assertThat(admission.sessionsOf(ALICE)).isEqualTo(1);
        assertThat(admission.handshaking()).isZero();
        assertThat(ports.snapshot().reserved()).isEqualTo(2);
    }

    @Test
    void aPlannedTunnelBecomesATunnelOnThePublicHost() {
        Registration registration = registerTunnels.execute(ticket(), register(ALICE_TOKEN, tunnel("ssh", 21_001)));
        PlannedTunnel planned = registration.tunnels().getFirst();

        Tunnel tunnel = planned.toTunnel(registerTunnels.policy().publicHost());

        assertThat(tunnel.toRegistered().url()).isEqualTo("tcp://localhost:21001");
        assertThat(tunnel.id()).isEqualTo(planned.id());
        assertThat(tunnel.ref()).isEqualTo("ssh");
    }

    @Test
    void refusesMoreTunnelsThanThePolicyAllowsBeforeCheckingTheToken() {
        HandshakeTicket ticket = ticket();

        assertRefused(
                () -> registerTunnels.execute(
                        ticket, register("jb_unknown", tunnel("a", null), tunnel("b", null), tunnel("c", null))),
                ErrorCode.TUNNEL_LIMIT_EXCEEDED);

        assertThat(verifier.calls()).isZero();
        assertThat(admission.handshaking()).isEqualTo(1);
    }

    @Test
    void refusesAnUnknownAuthtoken() {
        assertRefused(
                () -> registerTunnels.execute(ticket(), register("jb_unknown", tunnel("a", null))),
                ErrorCode.AUTHTOKEN_INVALID);

        assertThat(admission.handshaking()).isEqualTo(1);
        assertThat(ports.snapshot().reserved()).isZero();
    }

    @Test
    void refusesAPrincipalAtItsSessionCapBeforeReservingPorts() {
        registerTunnels.execute(ticket(), register(ALICE_TOKEN, tunnel("a", null)));

        assertRefused(
                () -> registerTunnels.execute(ticket(), register(ALICE_TOKEN, tunnel("a", null))),
                ErrorCode.SESSION_LIMIT_EXCEEDED);

        assertThat(ports.snapshot().reserved()).isEqualTo(1);
    }

    @Test
    void refusesARequestedPortOutsideTheRangeNamingTheTunnel() {
        ProtocolException refused = assertRefused(
                () -> registerTunnels.execute(ticket(), register(ALICE_TOKEN, tunnel("ssh", 20_417))),
                ErrorCode.PORT_UNAVAILABLE);

        assertThat(refused.ref()).contains("ssh");
    }

    @Test
    void refusesARequestedPortInUse() {
        ports.reserve(OptionalInt.of(21_000));

        ProtocolException refused = assertRefused(
                () -> registerTunnels.execute(ticket(), register(ALICE_TOKEN, tunnel("ssh", 21_000))),
                ErrorCode.PORT_UNAVAILABLE);

        assertThat(refused.ref()).contains("ssh");
    }

    @Test
    void refusesWhenThePoolIsExhausted() {
        for (int i = 0; i < 3; i++) {
            ports.reserve(OptionalInt.empty());
        }

        assertRefused(
                () -> registerTunnels.execute(ticket(), register(ALICE_TOKEN, tunnel("ssh", null))),
                ErrorCode.PORT_POOL_EXHAUSTED);
    }

    @Test
    void aFailureOnTheSecondTunnelReleasesTheFirstPort() {
        ProtocolException refused = assertRefused(
                () -> registerTunnels.execute(
                        ticket(), register(ALICE_TOKEN, tunnel("first", 21_001), tunnel("second", 20_000))),
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
        RegisterTunnels registering =
                new RegisterTunnels(verifier, admission, failingSecond, new RegistrationPolicy(2, "localhost"));

        assertThatThrownBy(() -> registering.execute(
                        ticket(), register(ALICE_TOKEN, tunnel("first", null), tunnel("second", null))))
                .isInstanceOf(IllegalStateException.class);

        assertThat(ports.snapshot().reserved()).isZero();
    }

    @Test
    void leavesTheAdmissionToTheTicketsReleaseWhenALaterStepFails() {
        HandshakeTicket ticket = ticket();

        assertRefused(
                () -> registerTunnels.execute(ticket, register(ALICE_TOKEN, tunnel("ssh", 20_000))),
                ErrorCode.PORT_UNAVAILABLE);
        assertThat(admission.sessionsOf(ALICE)).isEqualTo(1);

        admission.release(ticket);
        assertThat(admission.sessionsOf(ALICE)).isZero();
        assertThat(admission.activeSessions()).isZero();
    }

    @Test
    void releaseReturnsEveryPort() {
        Registration registration =
                registerTunnels.execute(ticket(), register(ALICE_TOKEN, tunnel("a", null), tunnel("b", null)));

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
                new RegistrationPolicy(2, "localhost"));

        Registration registration = registering.execute(ticket, register(ALICE_TOKEN, tunnel("a", null)));
        registering.release(registration);

        assertThat(admission.sessionsOf(ALICE)).isZero();
        assertThat(admission.activeSessions()).isZero();
        assertThat(ports.snapshot().reserved()).isZero();
    }

    private HandshakeTicket ticket() {
        return admission.tryBeginHandshake().orElseThrow();
    }

    private static Register register(String authtoken, TunnelRequest... tunnels) {
        return new Register(authtoken, List.of(tunnels));
    }

    private static TunnelRequest tunnel(String ref, Integer remotePort) {
        return new TunnelRequest(ref, TunnelKind.TCP, remotePort);
    }

    private static ProtocolException assertRefused(ThrowingCallable registration, ErrorCode code) {
        Throwable thrown = catchThrowable(registration);
        assertThat(thrown).isInstanceOf(ProtocolException.class);
        ProtocolException refused = (ProtocolException) thrown;
        assertThat(refused.code()).isEqualTo(code);
        return refused;
    }
}
