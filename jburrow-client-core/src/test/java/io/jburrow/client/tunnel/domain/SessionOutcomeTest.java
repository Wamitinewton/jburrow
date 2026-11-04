package io.jburrow.client.tunnel.domain;

import static org.assertj.core.api.Assertions.assertThat;

import io.jburrow.client.tunnel.domain.SessionOutcome.Reconnect;
import io.jburrow.client.tunnel.domain.SessionOutcome.RetryWithoutPinned;
import io.jburrow.client.tunnel.domain.SessionOutcome.Stop;
import io.jburrow.client.tunnel.domain.SessionOutcome.Wait;
import io.jburrow.protocol.control.RegisteredTunnel;
import io.jburrow.protocol.control.SubdomainName;
import io.jburrow.protocol.control.TunnelKind;
import io.jburrow.protocol.wire.ErrorCode;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Every row of ARCHITECTURE.md §When to give up, phase 4, for the first session and for later ones. */
class SessionOutcomeTest {

    private static final LocalTarget WEB = LocalTarget.of("localhost", 3000);
    private static final TunnelSpec RANDOM_NAME = TunnelSpec.http("default", WEB);
    private static final TunnelSpec CHOSEN_NAME =
            TunnelSpec.http("default", WEB).subdomain(new SubdomainName("myapp"));
    private static final TunnelSpec RANDOM_PORT = TunnelSpec.tcp("default", WEB);
    private static final TunnelSpec CHOSEN_PORT = TunnelSpec.tcp("default", WEB).remotePort(20_417);
    private static final UUID TUNNEL_ID = UUID.fromString("5d0f4f0e-31a6-4a39-8d51-0c0f7f1f6c23");
    private static final RegisteredTunnel OTTER =
            RegisteredTunnel.http("default", TUNNEL_ID, "https", "quiet-otter-4f2a.example.dev", 443);
    private static final RegisteredTunnel PORT =
            RegisteredTunnel.of("default", TUNNEL_ID, TunnelKind.TCP, "tcp.example.dev", 20_417);

    // Sessions that ended after being established.

    @Test
    void aLostConnectionIsRetried() {
        assertThat(afterSession(TunnelClosed.connectionLost("gone"))).isEqualTo(reconnect(new Wait.AfterLoss()));
    }

    @Test
    void aHeartbeatTimeoutIsRetriedWhicheverSideNoticed() {
        assertThat(afterSession(TunnelClosed.localError(ErrorCode.HEARTBEAT_TIMEOUT, "silent")))
                .isEqualTo(reconnect(new Wait.AfterLoss()));
        assertThat(afterSession(TunnelClosed.serverError(ErrorCode.HEARTBEAT_TIMEOUT, "silent")))
                .isEqualTo(reconnect(new Wait.AfterLoss()));
    }

    @ParameterizedTest
    @EnumSource(
            value = ErrorCode.class,
            names = {"SERVER_SHUTTING_DOWN", "SERVER_AT_CAPACITY", "INTERNAL_ERROR", "HANDSHAKE_TIMEOUT"})
    void theServersPassingTroublesAreRetried(ErrorCode code) {
        assertThat(afterSession(TunnelClosed.serverError(code, "x"))).isEqualTo(reconnect(new Wait.AfterLoss()));
    }

    @Test
    void anEndAfterGoAwayWaitsForTheServersDelay() {
        SessionEnd end = SessionEnd.ofSession(
                TunnelClosed.serverError(ErrorCode.SERVER_SHUTTING_DOWN, "draining"),
                Optional.of(Duration.ofMillis(830)));

        assertThat(SessionOutcome.of(end, false, List.of(RANDOM_NAME), Resumption.none()))
                .isEqualTo(reconnect(new Wait.AfterGoAway(Duration.ofMillis(830))));
    }

    @Test
    void aLostConnectionAfterGoAwayStillWaitsForTheServersDelay() {
        SessionEnd end = SessionEnd.ofSession(TunnelClosed.connectionLost("gone"), Optional.of(Duration.ofSeconds(2)));

        assertThat(SessionOutcome.of(end, false, List.of(RANDOM_NAME), Resumption.none()))
                .isEqualTo(reconnect(new Wait.AfterGoAway(Duration.ofSeconds(2))));
    }

    @ParameterizedTest
    @EnumSource(
            value = ErrorCode.class,
            names = {
                "UNSUPPORTED_PROTOCOL_VERSION",
                "PROTOCOL_ERROR",
                "FRAME_SIZE_ERROR",
                "FLOW_CONTROL_ERROR",
                "PING_RATE_EXCEEDED",
                "FORBIDDEN_ORIGIN",
                "SESSION_REPLACED"
            })
    void aSessionEndedByAMismatchOrAReplacementStops(ErrorCode code) {
        TunnelClosed closed = TunnelClosed.serverError(code, "x");

        assertThat(afterSession(closed)).isEqualTo(new Stop(closed));
        assertThat(afterSession(TunnelClosed.localError(code, "x"))).isInstanceOf(Stop.class);
    }

    @Test
    void aSessionEndedByAGoAwayAndThenAProtocolErrorStops() {
        TunnelClosed closed = TunnelClosed.localError(ErrorCode.PROTOCOL_ERROR, "OPEN after GOAWAY");
        SessionEnd end = SessionEnd.ofSession(closed, Optional.of(Duration.ofMillis(830)));

        assertThat(SessionOutcome.of(end, false, List.of(RANDOM_NAME), Resumption.none()))
                .isEqualTo(new Stop(closed));
    }

    @Test
    void theUserClosingStops() {
        assertThat(afterSession(TunnelClosed.userClosed())).isEqualTo(new Stop(TunnelClosed.userClosed()));
        assertThat(SessionOutcome.of(
                        SessionEnd.ofAttempt(
                                TunnelClosed.userClosed(), OptionalInt.empty(), Optional.empty(), Optional.empty()),
                        true,
                        List.of(RANDOM_NAME),
                        Resumption.none()))
                .isInstanceOf(Stop.class);
    }

    // Attempts that failed after the first session.

    @Test
    void anAttemptThatCouldntConnectCarriesTheRunOn() {
        assertThat(afterAttempt(TunnelClosed.connectionLost("refused"), OptionalInt.empty(), Optional.empty()))
                .isEqualTo(reconnect(new Wait.Continue()));
    }

    @ParameterizedTest
    @ValueSource(ints = {500, 502, 503, 504, 404, 400})
    void anUpgradeRefusedWithoutACodeCarriesTheRunOn(int status) {
        assertThat(afterAttempt(TunnelClosed.connectionLost("refused"), OptionalInt.of(status), Optional.empty()))
                .isEqualTo(reconnect(new Wait.Continue()));
    }

    @Test
    void anUpgradeRefusedWithRetryAfterWaitsForIt() {
        TunnelClosed full = TunnelClosed.serverError(ErrorCode.SERVER_AT_CAPACITY, "full");

        assertThat(afterAttempt(full, OptionalInt.of(503), Optional.of(Duration.ofSeconds(5))))
                .isEqualTo(reconnect(new Wait.AfterRetryAfter(Duration.ofSeconds(5))));
    }

    @ParameterizedTest
    @EnumSource(
            value = ErrorCode.class,
            names = {"NOT_FOUND", "FORBIDDEN_ORIGIN", "UNSUPPORTED_PROTOCOL_VERSION", "PROTOCOL_ERROR"})
    void anUpgradeRefusedWithACodeThatCantChangeStops(ErrorCode code) {
        TunnelClosed refused = TunnelClosed.serverError(code, "no");

        assertThat(afterAttempt(refused, OptionalInt.of(400), Optional.empty())).isEqualTo(new Stop(refused));
    }

    @Test
    void anInvalidAuthtokenStops() {
        TunnelClosed refused = TunnelClosed.serverError(ErrorCode.AUTHTOKEN_INVALID, "unknown");

        assertThat(afterAttempt(refused, OptionalInt.empty(), Optional.empty())).isEqualTo(new Stop(refused));
    }

    @ParameterizedTest
    @EnumSource(
            value = ErrorCode.class,
            names = {
                "SUBDOMAIN_FORBIDDEN",
                "TUNNEL_LIMIT_EXCEEDED",
                "SESSION_LIMIT_EXCEEDED",
                "PORT_POOL_EXHAUSTED",
                "VALIDATION_FAILED"
            })
    void anyOtherRegistrationRefusalStops(ErrorCode code) {
        TunnelClosed refused = TunnelClosed.serverError(code, "no");

        assertThat(outcome(refused, false, RANDOM_NAME, pinnedTo(RANDOM_NAME, OTTER)))
                .isEqualTo(new Stop(refused));
    }

    @Test
    void aClientWithoutTheCapabilityItsTunnelsNeedStops() {
        TunnelClosed unsupported =
                new TunnelClosed(TunnelClosed.Reason.LOCAL_ERROR, Optional.empty(), "No HTTP tunnels here.");

        assertThat(afterAttempt(unsupported, OptionalInt.empty(), Optional.empty()))
                .isEqualTo(new Stop(unsupported));
    }

    // A name or port taken in between.

    @Test
    void aNameTheServerChoseThatIsNowTakenIsAskedForAgainWithoutIt() {
        TunnelClosed taken = TunnelClosed.serverError(ErrorCode.SUBDOMAIN_UNAVAILABLE, "taken");

        assertThat(outcome(taken, false, RANDOM_NAME, pinnedTo(RANDOM_NAME, OTTER)))
                .isEqualTo(new RetryWithoutPinned("default"));
    }

    @Test
    void aPortTheServerChoseThatIsNowTakenIsAskedForAgainWithoutIt() {
        TunnelClosed taken = TunnelClosed.serverError(ErrorCode.PORT_UNAVAILABLE, "taken");

        assertThat(outcome(taken, false, RANDOM_PORT, pinnedTo(RANDOM_PORT, PORT)))
                .isEqualTo(new RetryWithoutPinned("default"));
    }

    @Test
    void aNameOrPortTheUserChoseThatIsTakenStops() {
        TunnelClosed name = TunnelClosed.serverError(ErrorCode.SUBDOMAIN_UNAVAILABLE, "taken");
        TunnelClosed port = TunnelClosed.serverError(ErrorCode.PORT_UNAVAILABLE, "taken");

        assertThat(outcome(name, false, CHOSEN_NAME, pinnedTo(CHOSEN_NAME, OTTER)))
                .isEqualTo(new Stop(name));
        assertThat(outcome(port, false, CHOSEN_PORT, pinnedTo(CHOSEN_PORT, PORT)))
                .isEqualTo(new Stop(port));
    }

    @Test
    void theRetryWithoutTheServersChoiceHappensOnce() {
        TunnelClosed taken = TunnelClosed.serverError(ErrorCode.SUBDOMAIN_UNAVAILABLE, "taken");
        Resumption once = pinnedTo(RANDOM_NAME, OTTER).withoutPin("default");

        assertThat(outcome(taken, false, RANDOM_NAME, once)).isEqualTo(new Stop(taken));
    }

    @Test
    void aRefusalNamingAnotherTunnelIsNotAboutThePinnedOne() {
        TunnelClosed taken = TunnelClosed.serverError(ErrorCode.SUBDOMAIN_UNAVAILABLE, "taken");
        SessionEnd end =
                SessionEnd.ofAttempt(taken, OptionalInt.empty(), Optional.empty(), Optional.of("someone-else"));

        assertThat(SessionOutcome.of(end, false, List.of(RANDOM_NAME), pinnedTo(RANDOM_NAME, OTTER)))
                .isEqualTo(new Stop(taken));
    }

    // The first session.

    @Test
    void aFirstSessionThatCantConnectFailsAtOnce() {
        TunnelClosed lost = TunnelClosed.connectionLost("refused");

        assertThat(firstAttempt(lost, OptionalInt.empty(), Optional.empty())).isEqualTo(new Stop(lost));
        assertThat(firstAttempt(
                        TunnelClosed.serverError(ErrorCode.INTERNAL_ERROR, "x"), OptionalInt.empty(), noRetryAfter()))
                .isInstanceOf(Stop.class);
        assertThat(firstAttempt(lost, OptionalInt.of(500), noRetryAfter())).isInstanceOf(Stop.class);
        assertThat(firstAttempt(lost, OptionalInt.of(404), noRetryAfter())).isInstanceOf(Stop.class);
    }

    @ParameterizedTest
    @ValueSource(ints = {502, 503, 504})
    void aFirstSessionRefusedByAGatewayInTheMiddleOfADeployIsRetried(int status) {
        assertThat(firstAttempt(TunnelClosed.connectionLost("refused"), OptionalInt.of(status), noRetryAfter()))
                .isEqualTo(reconnect(new Wait.StartingUp(Duration.ZERO)));
    }

    @Test
    void aFirstSessionToAServerThatIsStoppingOrFullIsRetried() {
        assertThat(firstAttempt(
                        TunnelClosed.serverError(ErrorCode.SERVER_SHUTTING_DOWN, "stopping"),
                        OptionalInt.empty(),
                        noRetryAfter()))
                .isEqualTo(reconnect(new Wait.StartingUp(Duration.ZERO)));
        assertThat(firstAttempt(
                        TunnelClosed.serverError(ErrorCode.SERVER_AT_CAPACITY, "full"),
                        OptionalInt.of(503),
                        Optional.of(Duration.ofSeconds(5))))
                .isEqualTo(reconnect(new Wait.StartingUp(Duration.ofSeconds(5))));
    }

    @Test
    void aFirstSessionStopsOnEveryRefusalALaterOneWould() {
        TunnelClosed refused = TunnelClosed.serverError(ErrorCode.AUTHTOKEN_INVALID, "unknown");
        TunnelClosed taken = TunnelClosed.serverError(ErrorCode.SUBDOMAIN_UNAVAILABLE, "taken");

        assertThat(firstAttempt(refused, OptionalInt.empty(), noRetryAfter())).isEqualTo(new Stop(refused));
        assertThat(outcome(taken, true, CHOSEN_NAME, Resumption.none())).isEqualTo(new Stop(taken));
        assertThat(outcome(taken, true, RANDOM_NAME, Resumption.none())).isEqualTo(new Stop(taken));
    }

    private static SessionOutcome afterSession(TunnelClosed closed) {
        return SessionOutcome.of(
                SessionEnd.ofSession(closed, Optional.empty()), false, List.of(RANDOM_NAME), Resumption.none());
    }

    private static SessionOutcome afterAttempt(TunnelClosed closed, OptionalInt status, Optional<Duration> retry) {
        return SessionOutcome.of(
                SessionEnd.ofAttempt(closed, status, retry, Optional.empty()),
                false,
                List.of(RANDOM_NAME),
                pinnedTo(RANDOM_NAME, OTTER));
    }

    private static SessionOutcome firstAttempt(TunnelClosed closed, OptionalInt status, Optional<Duration> retry) {
        return SessionOutcome.of(
                SessionEnd.ofAttempt(closed, status, retry, Optional.empty()),
                true,
                List.of(RANDOM_NAME),
                Resumption.none());
    }

    private static SessionOutcome outcome(
            TunnelClosed closed, boolean firstSession, TunnelSpec spec, Resumption resumption) {
        return SessionOutcome.of(
                SessionEnd.ofAttempt(closed, OptionalInt.empty(), Optional.empty(), Optional.of(spec.ref())),
                firstSession,
                List.of(spec),
                resumption);
    }

    private static Resumption pinnedTo(TunnelSpec spec, RegisteredTunnel registered) {
        return Resumption.after(List.of(spec), List.of(registered), Optional.empty());
    }

    private static Reconnect reconnect(Wait wait) {
        return new Reconnect(wait);
    }

    private static Optional<Duration> noRetryAfter() {
        return Optional.empty();
    }
}
