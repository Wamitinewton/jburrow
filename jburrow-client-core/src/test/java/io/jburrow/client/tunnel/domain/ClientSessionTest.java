package io.jburrow.client.tunnel.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.groups.Tuple.tuple;

import io.jburrow.protocol.control.Open;
import io.jburrow.protocol.control.Register;
import io.jburrow.protocol.control.Registered;
import io.jburrow.protocol.control.RegisteredTunnel;
import io.jburrow.protocol.control.RemoteAddress;
import io.jburrow.protocol.control.ServerHello;
import io.jburrow.protocol.control.ServerInfo;
import io.jburrow.protocol.control.SessionLimits;
import io.jburrow.protocol.control.TunnelKind;
import io.jburrow.protocol.control.TunnelRequest;
import io.jburrow.protocol.stream.StreamEvent;
import io.jburrow.protocol.stream.StreamOutcome;
import io.jburrow.protocol.stream.StreamState;
import io.jburrow.protocol.wire.ErrorCode;
import io.jburrow.protocol.wire.FrameType;
import io.jburrow.protocol.wire.ProtocolException;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class ClientSessionTest {

    private static final UUID SESSION_ID = UUID.fromString("0b8e5c1e-7c55-4c46-9a3e-2f1d0c9e4b7a");
    private static final UUID TUNNEL_ID = UUID.fromString("5d0f4f0e-31a6-4a39-8d51-0c0f7f1f6c23");
    private static final UUID OTHER_TUNNEL_ID = UUID.fromString("6e1f5f1f-42b7-4b4a-9e62-1d1f8f2f7d34");
    private static final LocalTarget SSH = LocalTarget.of("localhost", 22);
    private static final SessionLimits LIMITS = new SessionLimits(1_024, 1, 1, 15_000, 45_000);
    private static final RemoteAddress VISITOR = new RemoteAddress("203.0.113.9", 51_234);

    private final ClientSession session = new ClientSession(List.of(TunnelSpec.tcp("default", SSH)));

    // Frame order, docs/protocol.md §8.2, from the server's side.

    @ParameterizedTest(name = "{0} from the server before the upgrade is PROTOCOL_ERROR")
    @EnumSource(FrameType.class)
    void nothingIsAllowedBeforeTheUpgrade(FrameType type) {
        assertProtocolError(() -> session.checkInbound(type));
    }

    @ParameterizedTest(name = "{0} while awaiting HELLO")
    @EnumSource(FrameType.class)
    void onlyHelloOrErrorMayComeFirst(FrameType type) {
        session.onUpgraded();

        assertAllowedOnlyIn(type, EnumSet.of(FrameType.HELLO, FrameType.ERROR));
    }

    @ParameterizedTest(name = "{0} while awaiting the REGISTER reply")
    @EnumSource(FrameType.class)
    void afterHelloTheReplyPingPongOrErrorMayCome(FrameType type) {
        awaitingRegistered();

        assertAllowedOnlyIn(type, EnumSet.of(FrameType.REGISTER, FrameType.PING, FrameType.PONG, FrameType.ERROR));
    }

    @ParameterizedTest(name = "{0} once established")
    @EnumSource(FrameType.class)
    void onceEstablishedStreamsPingPongAndErrorMayCome(FrameType type) {
        established();

        assertAllowedOnlyIn(
                type,
                EnumSet.of(
                        FrameType.OPEN,
                        FrameType.DATA,
                        FrameType.CLOSE,
                        FrameType.PING,
                        FrameType.PONG,
                        FrameType.ERROR));
    }

    @ParameterizedTest(name = "{0} while closing passes, to be ignored")
    @EnumSource(FrameType.class)
    void everythingPassesWhileClosing(FrameType type) {
        established();
        session.onClosing();

        assertThatCode(() -> session.checkInbound(type)).doesNotThrowAnyException();
    }

    @Test
    void aSecondHelloIsAProtocolError() {
        awaitingRegistered();

        assertProtocolError(() -> session.onServerHello(serverHello()));
    }

    @Test
    void aReplyBeforeTheServersHelloIsAProtocolError() {
        session.onUpgraded();

        assertProtocolError(() -> session.onRegistered(new Registered(List.of(registered("default", 20_417)))));
    }

    @Test
    void theServersHelloSetsTheSessionIdAndLimits() {
        session.onUpgraded();

        assertThat(session.maxFramePayload()).isEqualTo(65_536);
        assertThat(session.onServerHello(serverHello())).isEqualTo(LIMITS);
        assertThat(session.sessionId()).contains(SESSION_ID);
        assertThat(session.maxFramePayload()).isEqualTo(1_024);
        assertThat(session.state()).isEqualTo(ClientSessionState.AWAITING_REGISTERED);
    }

    // Registration, docs/protocol.md §8.4.

    @Test
    void theRegisterRequestCarriesTheTokenAndEachTunnelInOrder() {
        ClientSession two = new ClientSession(List.of(
                TunnelSpec.tcp("ssh", SSH),
                TunnelSpec.tcp("db", LocalTarget.of("127.0.0.1", 5_432)).remotePort(20_500)));

        Register register = two.register("jb_token");

        assertThat(register.authtoken()).isEqualTo("jb_token");
        assertThat(register.tunnels())
                .extracting(TunnelRequest::ref, TunnelRequest::remotePort)
                .containsExactly(tuple("ssh", null), tuple("db", 20_500));
    }

    @Test
    void aMatchingReplyEstablishesTheSessionWithEachTunnelsLocalTarget() {
        awaitingRegistered();

        List<ActiveTunnel> tunnels = session.onRegistered(new Registered(List.of(registered("default", 20_417))));

        assertThat(session.isEstablished()).isTrue();
        assertThat(tunnels).singleElement().satisfies(tunnel -> {
            assertThat(tunnel.id()).isEqualTo(TUNNEL_ID);
            assertThat(tunnel.ref()).isEqualTo("default");
            assertThat(tunnel.target()).isEqualTo(SSH);
        });
        assertThat(session.tunnels()).isEqualTo(tunnels);
    }

    @Test
    void aReplyWithADifferentNumberOfTunnelsIsAProtocolError() {
        awaitingRegistered();

        assertProtocolError(() -> session.onRegistered(new Registered(List.of(
                registered("default", 20_417),
                RegisteredTunnel.of("extra", OTHER_TUNNEL_ID, TunnelKind.TCP, "tcp.example.dev", 20_418)))));
    }

    @Test
    void aReplyForADifferentRefIsAProtocolError() {
        awaitingRegistered();

        assertProtocolError(() -> session.onRegistered(new Registered(List.of(registered("other", 20_417)))));
    }

    @Test
    void aReplyInADifferentOrderIsAProtocolError() {
        ClientSession two = new ClientSession(List.of(TunnelSpec.tcp("first", SSH), TunnelSpec.tcp("second", SSH)));
        two.onUpgraded();
        two.onServerHello(serverHello());

        assertProtocolError(() -> two.onRegistered(new Registered(List.of(
                RegisteredTunnel.of("second", TUNNEL_ID, TunnelKind.TCP, "tcp.example.dev", 20_417),
                RegisteredTunnel.of("first", OTHER_TUNNEL_ID, TunnelKind.TCP, "tcp.example.dev", 20_418)))));
    }

    @Test
    void aReplyOnAnotherPortThanTheOneRequestedIsAProtocolError() {
        ClientSession requesting =
                new ClientSession(List.of(TunnelSpec.tcp("default", SSH).remotePort(20_417)));
        requesting.onUpgraded();
        requesting.onServerHello(serverHello());

        assertProtocolError(() -> requesting.onRegistered(new Registered(List.of(registered("default", 20_999)))));
    }

    @Test
    void aReplyRepeatingATunnelIdIsAProtocolError() {
        ClientSession two = new ClientSession(List.of(TunnelSpec.tcp("first", SSH), TunnelSpec.tcp("second", SSH)));
        two.onUpgraded();
        two.onServerHello(serverHello());

        assertProtocolError(() -> two.onRegistered(new Registered(List.of(
                RegisteredTunnel.of("first", TUNNEL_ID, TunnelKind.TCP, "tcp.example.dev", 20_417),
                RegisteredTunnel.of("second", TUNNEL_ID, TunnelKind.TCP, "tcp.example.dev", 20_418)))));
    }

    @Test
    void aSecondReplyIsAProtocolError() {
        established();

        assertProtocolError(() -> session.onRegistered(new Registered(List.of(registered("default", 20_417)))));
    }

    // Streams, docs/protocol.md §9.

    @Test
    void anOpenForAKnownTunnelOpensTheStream() {
        established();

        ActiveTunnel tunnel = session.onOpen(1, open(TUNNEL_ID));

        assertThat(tunnel.target()).isEqualTo(SSH);
        assertThat(session.streamState(1)).contains(StreamState.OPEN);
        assertThat(session.openStreamIds()).containsExactly(1);
    }

    @Test
    void anOpenBeforeTheSessionIsEstablishedIsAProtocolError() {
        awaitingRegistered();

        assertProtocolError(() -> session.onOpen(1, open(TUNNEL_ID)));
    }

    @Test
    void anOpenWhoseIdIsNotAboveEveryIdSeenIsAProtocolError() {
        established();
        session.onOpen(2, open(TUNNEL_ID));

        assertProtocolError(() -> session.onOpen(2, open(TUNNEL_ID)));
        assertProtocolError(() -> session.onOpen(1, open(TUNNEL_ID)));
    }

    @Test
    void anOpenForAnUnknownTunnelIsAProtocolError() {
        established();

        assertProtocolError(() -> session.onOpen(1, open(OTHER_TUNNEL_ID)));
    }

    @Test
    void theClientDoesNotCheckTheConcurrentStreamLimit() {
        established();
        session.onOpen(1, open(TUNNEL_ID));

        assertThatCode(() -> session.onOpen(2, open(TUNNEL_ID))).doesNotThrowAnyException();
        assertThat(session.openStreamIds()).containsExactly(1, 2);
    }

    @Test
    void aFrameForAStreamNeverOpenedIsAProtocolError() {
        established();
        session.onOpen(1, open(TUNNEL_ID));

        assertProtocolError(() -> session.onStreamFrame(2, StreamEvent.DATA_RECEIVED));
    }

    @Test
    void aFrameForAClosedStreamIsDiscarded() {
        established();
        session.onOpen(1, open(TUNNEL_ID));
        session.onStreamFrame(1, StreamEvent.RST_RECEIVED);

        assertThat(session.streamState(1)).isEmpty();
        assertThat(session.onStreamFrame(1, StreamEvent.DATA_RECEIVED)).isInstanceOf(StreamOutcome.Discard.class);
        assertThat(session.openStreamIds()).isEmpty();
    }

    @Test
    void dataAfterTheServersOwnCloseIsAStreamErrorThatClosesTheStream() {
        established();
        session.onOpen(1, open(TUNNEL_ID));
        session.onStreamFrame(1, StreamEvent.CLOSE_RECEIVED);

        assertThat(session.onStreamFrame(1, StreamEvent.DATA_RECEIVED))
                .isEqualTo(new StreamOutcome.ResetStream(ErrorCode.STREAM_CLOSED));
        assertThat(session.streamState(1)).isEmpty();
    }

    @Test
    void bothHalvesClosedClosesTheStream() {
        established();
        session.onOpen(1, open(TUNNEL_ID));
        session.onStreamSent(1, StreamEvent.CLOSE_SENT);

        assertThat(session.streamState(1)).contains(StreamState.HALF_CLOSED_LOCAL);
        session.onStreamFrame(1, StreamEvent.CLOSE_RECEIVED);
        assertThat(session.streamState(1)).isEmpty();
    }

    @Test
    void sendingOnAStreamThatIsNotOpenIsABug() {
        established();

        assertThatThrownBy(() -> session.onStreamSent(1, StreamEvent.DATA_SENT))
                .isInstanceOf(IllegalStateException.class);
    }

    // Construction.

    @Test
    void aSessionNeedsTunnelsWithDistinctRefs() {
        assertThatThrownBy(() -> new ClientSession(List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ClientSession(List.of(TunnelSpec.tcp("same", SSH), TunnelSpec.tcp("same", SSH))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private void awaitingRegistered() {
        session.onUpgraded();
        session.onServerHello(serverHello());
    }

    private void established() {
        awaitingRegistered();
        session.onRegistered(new Registered(List.of(registered("default", 20_417))));
    }

    private void assertAllowedOnlyIn(FrameType type, Set<FrameType> allowed) {
        if (allowed.contains(type)) {
            assertThatCode(() -> session.checkInbound(type)).doesNotThrowAnyException();
        } else {
            assertProtocolError(() -> session.checkInbound(type));
        }
    }

    private static void assertProtocolError(ThrowingCallable call) {
        assertThatThrownBy(call)
                .isInstanceOfSatisfying(
                        ProtocolException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.PROTOCOL_ERROR));
    }

    private static ServerHello serverHello() {
        return new ServerHello(SESSION_ID, new ServerInfo("jburrow-server", "0.2.0"), List.of(), LIMITS);
    }

    private static RegisteredTunnel registered(String ref, int port) {
        return RegisteredTunnel.of(ref, TUNNEL_ID, TunnelKind.TCP, "tcp.example.dev", port);
    }

    private static Open open(UUID tunnelId) {
        return new Open(tunnelId, TunnelKind.TCP, VISITOR);
    }
}
