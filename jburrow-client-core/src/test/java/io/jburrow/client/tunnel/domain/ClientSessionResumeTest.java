package io.jburrow.client.tunnel.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import io.jburrow.protocol.control.ClientInfo;
import io.jburrow.protocol.control.GoAway;
import io.jburrow.protocol.control.Open;
import io.jburrow.protocol.control.Register;
import io.jburrow.protocol.control.Registered;
import io.jburrow.protocol.control.RegisteredTunnel;
import io.jburrow.protocol.control.RemoteAddress;
import io.jburrow.protocol.control.ResumeToken;
import io.jburrow.protocol.control.ServerHello;
import io.jburrow.protocol.control.ServerInfo;
import io.jburrow.protocol.control.SessionLimits;
import io.jburrow.protocol.control.TunnelKind;
import io.jburrow.protocol.control.TunnelRequest;
import io.jburrow.protocol.control.Windows;
import io.jburrow.protocol.flow.DataOutcome;
import io.jburrow.protocol.stream.StreamEvent;
import io.jburrow.protocol.stream.StreamState;
import io.jburrow.protocol.wire.Capability;
import io.jburrow.protocol.wire.ErrorCode;
import io.jburrow.protocol.wire.FrameType;
import io.jburrow.protocol.wire.ProtocolException;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * {@link ClientSession} with the {@code resume} capability: the token in {@code REGISTER} and its reply, and the
 * client-side {@code GOAWAY} rules of {@code docs/protocol.md} §6.10. Tokens are compared, never printed.
 */
class ClientSessionResumeTest {

    private static final UUID SESSION_ID = UUID.fromString("0b8e5c1e-7c55-4c46-9a3e-2f1d0c9e4b7a");
    private static final UUID TUNNEL_ID = UUID.fromString("5d0f4f0e-31a6-4a39-8d51-0c0f7f1f6c23");
    private static final LocalTarget SSH = LocalTarget.of("localhost", 22);
    private static final SessionLimits LIMITS = new SessionLimits(65_536, 1, 100, 15_000, 45_000);
    private static final Windows WINDOWS = new Windows(262_144, 524_288);
    private static final ClientInfo CLIENT = new ClientInfo("jburrow-test", "1.0.0", null, null);
    private static final Set<Capability> RESUME = Set.of(Capability.RESUME);
    private static final ResumeToken PREVIOUS = new ResumeToken("jbr_AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8");
    private static final ResumeToken ISSUED = new ResumeToken("jbr_ICEiIyQlJicoKSorLC0uLzAxMjM0NTY3ODk6Ozw9Pj8");
    private static final Resumption PREVIOUS_SESSION = Resumption.after(List.of(), List.of(), Optional.of(PREVIOUS));
    private static final GoAway GO_AWAY = new GoAway(830, 5_000);
    private static final String AUTHTOKEN = "jb_x";

    private final ClientSession session = new ClientSession(List.of(TunnelSpec.tcp("default", SSH)), WINDOWS, RESUME);

    // Negotiation and the token, docs/protocol.md §4.2, §7.5 and §7.6.

    @Test
    void offersResumeInItsHello() {
        assertThat(session.hello(CLIENT).capabilities()).containsExactly("resume");
    }

    @Test
    void aSessionWithoutATokenRegistersWithItsHello() {
        assertThat(session.registersAfterHello()).isFalse();
    }

    @Test
    void aSessionWithATokenWaitsForTheServersHello() {
        ClientSession resuming = resuming();

        assertThat(resuming.registersAfterHello()).isTrue();
        assertThat(resuming.missingCapability()).isEmpty();
    }

    @Test
    void theTokenGoesInRegisterOnlyWhenTheServerListsResume() {
        ClientSession withResume = resuming();
        withResume.onUpgraded();
        withResume.onServerHello(serverHello(List.of("resume")));
        ClientSession withoutResume = resuming();
        withoutResume.onUpgraded();
        withoutResume.onServerHello(serverHello(List.of()));

        assertThat(withResume.register(AUTHTOKEN).resume()).isEqualTo(PREVIOUS);
        assertThat(withoutResume.register(AUTHTOKEN).resume()).isNull();
        assertThat(withoutResume.missingCapability()).isEmpty();
    }

    @Test
    void aTokenNeedsASessionThatOffersResume() {
        assertThatIllegalArgumentException()
                .isThrownBy(() ->
                        new ClientSession(List.of(TunnelSpec.tcp("default", SSH)), WINDOWS, Set.of(), PREVIOUS_SESSION))
                .withMessage("A resume token needs a session that offers the resume capability");
    }

    @Test
    void theReplysTokenIsKeptForTheNextSession() {
        established();

        assertThat(session.resumeEnabled()).isTrue();
        assertThat(session.resumeToken()).contains(ISSUED);
    }

    @Test
    void aReplyWithoutATokenInASessionWithResumeIsAProtocolError() {
        awaitingRegistered();

        assertProtocolError(() -> session.onRegistered(new Registered(List.of(tunnel()))));
    }

    @Test
    void withoutResumeTheReplysTokenIsIgnored() {
        session.onUpgraded();
        session.onServerHello(serverHello(List.of()));
        session.onRegistered(new Registered(List.of(tunnel()), ISSUED));

        assertThat(session.resumeEnabled()).isFalse();
        assertThat(session.resumeToken()).isEmpty();
    }

    // GOAWAY, docs/protocol.md §6.10.

    @Test
    void goAwayMovesAnEstablishedSessionToDraining() {
        established();

        assertThat(session.onGoAway(GO_AWAY)).isEqualTo(GO_AWAY);
        assertThat(session.state()).isEqualTo(ClientSessionState.DRAINING);
        assertThat(session.isDraining()).isTrue();
        assertThat(session.isEstablished()).isTrue();
        assertThat(session.isClosing()).isFalse();
    }

    @Test
    void goAwayIsAllowedOnceEstablished() {
        established();

        assertThatCode(() -> session.checkInbound(FrameType.GOAWAY)).doesNotThrowAnyException();
    }

    @Test
    void goAwayBeforeTheReplyIsAProtocolError() {
        awaitingRegistered();

        assertProtocolError(() -> session.checkInbound(FrameType.GOAWAY));
        assertProtocolError(() -> session.onGoAway(GO_AWAY));
        assertThat(session.state()).isEqualTo(ClientSessionState.AWAITING_REGISTERED);
    }

    @Test
    void goAwayBeforeTheServersHelloIsAProtocolError() {
        session.onUpgraded();

        assertProtocolError(() -> session.checkInbound(FrameType.GOAWAY));
        assertProtocolError(() -> session.onGoAway(GO_AWAY));
    }

    @Test
    void aSecondGoAwayIsAProtocolError() {
        established();
        session.onGoAway(GO_AWAY);

        assertProtocolError(() -> session.checkInbound(FrameType.GOAWAY));
        assertProtocolError(() -> session.onGoAway(GO_AWAY));
    }

    @Test
    void openAfterGoAwayIsAProtocolError() {
        established();
        session.onGoAway(GO_AWAY);

        assertProtocolError(() -> session.checkInbound(FrameType.OPEN));
        assertProtocolError(() -> session.onOpen(1, open()));
    }

    @Test
    void withoutResumeEveryGoAwayIsAProtocolError() {
        ClientSession tcp = new ClientSession(List.of(TunnelSpec.tcp("default", SSH)), WINDOWS, Set.of());
        tcp.onUpgraded();
        tcp.onServerHello(serverHello(List.of()));
        tcp.onRegistered(new Registered(List.of(tunnel())));

        assertThatExceptionOfType(ProtocolException.class)
                .isThrownBy(() -> tcp.checkInbound(FrameType.GOAWAY))
                .withMessage("GOAWAY is not allowed in a session without the resume capability.");
        assertProtocolError(() -> tcp.onGoAway(GO_AWAY));
        assertThat(tcp.state()).isEqualTo(ClientSessionState.ESTABLISHED);
    }

    @Test
    void aSessionThatOfferedResumeButWasNotGivenItRefusesGoAway() {
        session.onUpgraded();
        session.onServerHello(serverHello(List.of()));
        session.onRegistered(new Registered(List.of(tunnel())));

        assertProtocolError(() -> session.checkInbound(FrameType.GOAWAY));
    }

    @ParameterizedTest(name = "{0} while draining")
    @EnumSource(FrameType.class)
    void whileDrainingOpenStreamsCarryOnAndNothingNewStarts(FrameType type) {
        established();
        session.onGoAway(GO_AWAY);

        Set<FrameType> allowed = EnumSet.of(
                FrameType.DATA,
                FrameType.WINDOW_UPDATE,
                FrameType.CLOSE,
                FrameType.PING,
                FrameType.PONG,
                FrameType.ERROR);
        if (allowed.contains(type)) {
            assertThatCode(() -> session.checkInbound(type)).doesNotThrowAnyException();
        } else {
            assertProtocolError(() -> session.checkInbound(type));
        }
    }

    @Test
    void aStreamOpenAtGoAwayFinishesNormally() {
        established();
        session.onOpen(1, open());
        session.onGoAway(GO_AWAY);

        assertThat(session.onDataReceived(1, 10)).isEqualTo(DataOutcome.DELIVER);
        session.onReleased(1, 10);
        session.onDataSent(1, 5);
        session.onStreamFrame(1, StreamEvent.CLOSE_RECEIVED);
        assertThat(session.streamState(1)).contains(StreamState.HALF_CLOSED_REMOTE);
        session.onStreamSent(1, StreamEvent.CLOSE_SENT);

        assertThat(session.openStreamIds()).isEmpty();
        assertThat(session.state()).isEqualTo(ClientSessionState.DRAINING);
    }

    @Test
    void aDrainingSessionStillCloses() {
        established();
        session.onGoAway(GO_AWAY);

        session.onClosing();

        assertThat(session.isClosing()).isTrue();
        assertThat(session.isEstablished()).isFalse();
        assertThatCode(() -> session.checkInbound(FrameType.GOAWAY)).doesNotThrowAnyException();
    }

    @Test
    void goAwayWhileClosingNeverReopensTheSession() {
        established();
        session.onClosing();

        assertProtocolError(() -> session.onGoAway(GO_AWAY));
        assertThat(session.isClosing()).isTrue();
    }

    // Registering from a Resumption: ARCHITECTURE.md §Asking for the same names, phase 4.

    @Test
    void aReconnectAsksForTheNameAndPortTheServerChose() {
        LocalTarget web = LocalTarget.of("localhost", 3000);
        List<TunnelSpec> asked = List.of(TunnelSpec.http("web", web), TunnelSpec.tcp("ssh", SSH));
        Resumption after = Resumption.after(
                asked,
                List.of(
                        RegisteredTunnel.http("web", TUNNEL_ID, "https", "quiet-otter-4f2a.example.dev", 443),
                        RegisteredTunnel.of("ssh", UUID.randomUUID(), TunnelKind.TCP, "tcp.example.dev", 20_417)),
                Optional.of(ISSUED));
        ClientSession next = new ClientSession(asked, WINDOWS, EnumSet.of(Capability.HTTP, Capability.RESUME), after);
        next.onUpgraded();
        next.onServerHello(serverHello(List.of("http", "resume")));

        Register register = next.register(AUTHTOKEN);

        assertThat(register.tunnels())
                .containsExactly(
                        new TunnelRequest("web", TunnelKind.HTTP, "quiet-otter-4f2a", null),
                        new TunnelRequest("ssh", TunnelKind.TCP, null, 20_417));
        assertThat(register.resume()).isEqualTo(ISSUED);
        assertThat(after.pin(asked.getFirst())).isEqualTo(Resumption.Pin.SERVER_CHOSEN);
        assertThat(after.toString()).doesNotContain(ISSUED.value());
    }

    @Test
    void aNameOrPortTheUserChoseIsAskedForAsBefore() {
        TunnelSpec chosen = TunnelSpec.tcp("default", SSH).remotePort(20_500);
        Resumption after = Resumption.after(List.of(chosen), List.of(tunnelOn(20_500)), Optional.empty());

        ClientSession next = new ClientSession(List.of(chosen), WINDOWS, RESUME, after);

        assertThat(next.register(AUTHTOKEN).tunnels().getFirst().remotePort()).isEqualTo(20_500);
        assertThat(after.pin(chosen)).isEqualTo(Resumption.Pin.USER_CHOSEN);
    }

    @Test
    void withoutItsPinATunnelLetsTheServerChooseAgainAndKeepsTheToken() {
        TunnelSpec random = TunnelSpec.tcp("default", SSH);
        Resumption without = Resumption.after(List.of(random), List.of(tunnel()), Optional.of(ISSUED))
                .withoutPin("default");

        ClientSession next = new ClientSession(List.of(random), WINDOWS, RESUME, without);
        next.onUpgraded();
        next.onServerHello(serverHello(List.of("resume")));

        assertThat(next.register(AUTHTOKEN).tunnels().getFirst().remotePort()).isNull();
        assertThat(next.register(AUTHTOKEN).resume()).isEqualTo(ISSUED);
        assertThat(without.pin(random)).isEqualTo(Resumption.Pin.NONE);
    }

    @Test
    void aReconnectWithATokenRegistersOnlyAfterTheServersHelloAndOmitsItWithoutResume() {
        TunnelSpec random = TunnelSpec.tcp("default", SSH);
        Resumption after = Resumption.after(List.of(random), List.of(tunnel()), Optional.of(ISSUED));
        ClientSession next = new ClientSession(List.of(random), WINDOWS, RESUME, after);

        assertThat(next.registersAfterHello()).isTrue();
        next.onUpgraded();
        next.onServerHello(serverHello(List.of()));

        Register register = next.register(AUTHTOKEN);
        assertThat(register.resume()).isNull();
        assertThat(register.tunnels().getFirst().remotePort()).isEqualTo(20_417);
    }

    @Test
    void theRepliedPinnedTunnelMustBeOnThePinnedPort() {
        TunnelSpec random = TunnelSpec.tcp("default", SSH);
        ClientSession next = new ClientSession(
                List.of(random),
                WINDOWS,
                Set.of(),
                Resumption.after(List.of(random), List.of(tunnel()), Optional.empty()));
        next.onUpgraded();
        next.onServerHello(serverHello(List.of()));

        assertProtocolError(() -> next.onRegistered(new Registered(List.of(tunnelOn(20_418)))));
    }

    private ClientSession resuming() {
        return new ClientSession(List.of(TunnelSpec.tcp("default", SSH)), WINDOWS, RESUME, PREVIOUS_SESSION);
    }

    private void awaitingRegistered() {
        session.onUpgraded();
        session.onServerHello(serverHello(List.of("resume")));
    }

    private void established() {
        awaitingRegistered();
        session.onRegistered(new Registered(List.of(tunnel()), ISSUED));
    }

    private static ServerHello serverHello(List<String> capabilities) {
        return new ServerHello(SESSION_ID, new ServerInfo("jburrow-server", "0.4.0"), capabilities, LIMITS, WINDOWS);
    }

    private static RegisteredTunnel tunnel() {
        return tunnelOn(20_417);
    }

    private static RegisteredTunnel tunnelOn(int port) {
        return RegisteredTunnel.of("default", TUNNEL_ID, TunnelKind.TCP, "tcp.example.dev", port);
    }

    private static Open open() {
        return new Open(TUNNEL_ID, TunnelKind.TCP, new RemoteAddress("203.0.113.9", 51_234));
    }

    private static void assertProtocolError(ThrowingCallable call) {
        assertThatExceptionOfType(ProtocolException.class)
                .isThrownBy(call)
                .satisfies(e -> assertThat(e.code()).isEqualTo(ErrorCode.PROTOCOL_ERROR));
    }
}
