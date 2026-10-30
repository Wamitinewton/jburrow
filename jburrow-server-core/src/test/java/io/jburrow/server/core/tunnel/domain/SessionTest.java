package io.jburrow.server.core.tunnel.domain;

import static io.jburrow.protocol.stream.StreamEvent.CLOSE_RECEIVED;
import static io.jburrow.protocol.stream.StreamEvent.CLOSE_SENT;
import static io.jburrow.protocol.stream.StreamEvent.DATA_RECEIVED;
import static io.jburrow.protocol.stream.StreamEvent.DATA_SENT;
import static io.jburrow.protocol.stream.StreamEvent.RST_RECEIVED;
import static io.jburrow.protocol.stream.StreamEvent.RST_SENT;
import static io.jburrow.protocol.stream.StreamEvent.WINDOW_UPDATE_RECEIVED;
import static io.jburrow.protocol.stream.StreamEvent.WINDOW_UPDATE_SENT;
import static io.jburrow.protocol.wire.FrameType.CLOSE;
import static io.jburrow.protocol.wire.FrameType.DATA;
import static io.jburrow.protocol.wire.FrameType.ERROR;
import static io.jburrow.protocol.wire.FrameType.HELLO;
import static io.jburrow.protocol.wire.FrameType.PING;
import static io.jburrow.protocol.wire.FrameType.PONG;
import static io.jburrow.protocol.wire.FrameType.REGISTER;
import static io.jburrow.protocol.wire.FrameType.WINDOW_UPDATE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import io.jburrow.protocol.control.ClientInfo;
import io.jburrow.protocol.control.Hello;
import io.jburrow.protocol.control.Windows;
import io.jburrow.protocol.flow.Credit;
import io.jburrow.protocol.flow.DataOutcome;
import io.jburrow.protocol.flow.UpdateTarget;
import io.jburrow.protocol.stream.StreamOutcome;
import io.jburrow.protocol.stream.StreamState;
import io.jburrow.protocol.wire.Capability;
import io.jburrow.protocol.wire.ErrorCode;
import io.jburrow.protocol.wire.FrameType;
import io.jburrow.protocol.wire.ProtocolException;
import io.jburrow.server.core.tunnel.domain.StreamAdmission.Refusal;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

class SessionTest {

    private static final Windows CLIENT_WINDOWS = new Windows(262_144, 1_048_576);
    /** Small enough that the tests reach every threshold with a few frames. */
    private static final Windows SERVER_WINDOWS = new Windows(65_536, 131_072);

    private static final int STREAM_THRESHOLD = SERVER_WINDOWS.stream() / 2;
    private static final int SESSION_THRESHOLD = SERVER_WINDOWS.session() / 2;
    private static final Hello HELLO_FRAME =
            new Hello(new ClientInfo("jburrow-cli", "0.2.0", "linux", "x86_64"), List.of(), CLIENT_WINDOWS);
    private static final Principal ALICE = new Principal("alice");
    private static final Tunnel TUNNEL = Tunnel.tcp(TunnelId.random(), "default", "localhost", 21_037);

    /** {@code docs/protocol.md} §8.2, the frames a client may send in each state. */
    private static final Map<SessionState, Set<FrameType>> ALLOWED = Map.of(
            SessionState.AWAITING_HELLO, EnumSet.of(HELLO, ERROR),
            SessionState.AWAITING_REGISTER, EnumSet.of(REGISTER, PING, PONG, ERROR),
            SessionState.REGISTERING, EnumSet.of(PING, PONG, ERROR),
            SessionState.ESTABLISHED, EnumSet.of(DATA, WINDOW_UPDATE, CLOSE, PING, PONG, ERROR),
            SessionState.CLOSING, EnumSet.allOf(FrameType.class));

    static Stream<Arguments> orderingTable() {
        List<Arguments> cells = new ArrayList<>();
        for (SessionState state : SessionState.values()) {
            for (FrameType type : FrameType.values()) {
                cells.add(arguments(state, type, ALLOWED.get(state).contains(type)));
            }
        }
        return cells.stream();
    }

    @ParameterizedTest(name = "{0} + {1} allowed: {2}")
    @MethodSource("orderingTable")
    void enforcesTheFrameOrderingTable(SessionState state, FrameType type, boolean allowed) {
        Session session = sessionIn(state, 1);

        if (allowed) {
            assertThatCode(() -> session.checkInbound(type)).doesNotThrowAnyException();
        } else {
            assertThatThrownBy(() -> session.checkInbound(type))
                    .isInstanceOfSatisfying(
                            ProtocolException.class,
                            error -> assertThat(error.code()).isEqualTo(ErrorCode.PROTOCOL_ERROR))
                    .hasMessageContaining(type.name());
        }
        assertThat(session.state()).isEqualTo(state);
    }

    @Test
    void walksTheHandshakeToEstablished() {
        Session session = new Session(SessionId.random(), 1, SERVER_WINDOWS);
        assertThat(session.state()).isEqualTo(SessionState.AWAITING_HELLO);

        session.onHello(HELLO_FRAME);
        assertThat(session.state()).isEqualTo(SessionState.AWAITING_REGISTER);
        assertThat(session.client()).contains(HELLO_FRAME.client());

        session.onRegisterReceived();
        assertThat(session.isRegistering()).isTrue();

        session.onEstablished(ALICE, List.of(TUNNEL));
        assertThat(session.state()).isEqualTo(SessionState.ESTABLISHED);
        assertThat(session.principal()).contains(ALICE);
        assertThat(session.tunnels()).containsExactly(TUNNEL);
        assertThat(session.tunnel(TUNNEL.id())).contains(TUNNEL);
        assertThat(session.tunnel(TunnelId.random())).isEmpty();
        assertThat(session.isRegistering()).isFalse();
    }

    @Test
    void theNegotiatedCapabilitiesAreThoseTheServerEnablesAndTheClientOffers() {
        Session session = new Session(SessionId.random(), 1, SERVER_WINDOWS, Set.of(Capability.HTTP));
        assertThat(session.capabilities()).isEmpty();
        assertThat(session.httpEnabled()).isFalse();

        session.onHello(hello(List.of("x-later", "http")));

        assertThat(session.capabilities()).containsExactly(Capability.HTTP);
        assertThat(session.httpEnabled()).isTrue();
    }

    @Test
    void aClientOfferingNothingGetsNoCapability() {
        Session session = new Session(SessionId.random(), 1, SERVER_WINDOWS, Set.of(Capability.HTTP));

        session.onHello(HELLO_FRAME);

        assertThat(session.capabilities()).isEmpty();
        assertThat(session.httpEnabled()).isFalse();
    }

    @Test
    void aServerEnablingNothingAgreesToNothing() {
        Session session = new Session(SessionId.random(), 1, SERVER_WINDOWS);

        session.onHello(hello(List.of("http")));

        assertThat(session.capabilities()).isEmpty();
        assertThat(session.httpEnabled()).isFalse();
    }

    @Test
    void aSecondHelloIsAProtocolError() {
        Session session = sessionIn(SessionState.AWAITING_REGISTER, 1);

        assertProtocolError(() -> session.onHello(HELLO_FRAME));
    }

    @Test
    void aSecondRegisterIsAProtocolError() {
        Session session = sessionIn(SessionState.REGISTERING, 1);

        assertProtocolError(session::onRegisterReceived);
    }

    @Test
    void registerBeforeHelloIsAProtocolError() {
        Session session = new Session(SessionId.random(), 1, SERVER_WINDOWS);

        assertProtocolError(session::onRegisterReceived);
    }

    @Test
    void establishingOutsideRegisteringIsABug() {
        Session session = sessionIn(SessionState.CLOSING, 1);

        assertThatIllegalStateException().isThrownBy(() -> session.onEstablished(ALICE, List.of(TUNNEL)));
    }

    @Test
    void anEstablishedSessionHasDistinctTunnels() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> sessionIn(SessionState.REGISTERING, 1).onEstablished(ALICE, List.of()));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> sessionIn(SessionState.REGISTERING, 1).onEstablished(ALICE, List.of(TUNNEL, TUNNEL)));
    }

    @ParameterizedTest
    @EnumSource(SessionState.class)
    void closingIsReachableFromEveryState(SessionState state) {
        Session session = sessionIn(state, 1);

        session.onClosing(ErrorCode.HEARTBEAT_TIMEOUT);

        assertThat(session.state()).isEqualTo(SessionState.CLOSING);
        assertThat(session.isRegistering()).isFalse();
    }

    @Test
    void closingIsIdempotentAndKeepsTheFirstReason() {
        Session session = sessionIn(SessionState.ESTABLISHED, 1);

        session.onClosing(ErrorCode.PING_RATE_EXCEEDED);
        session.onClosing(ErrorCode.SERVER_SHUTTING_DOWN);
        session.onClosing();

        assertThat(session.state()).isEqualTo(SessionState.CLOSING);
        assertThat(session.closeReason()).contains(ErrorCode.PING_RATE_EXCEEDED);
    }

    @Test
    void closingWithoutAnErrorHasNoReasonAndKeepsItThatWay() {
        Session session = sessionIn(SessionState.ESTABLISHED, 1);

        session.onClosing();
        session.onClosing(ErrorCode.HEARTBEAT_TIMEOUT);

        assertThat(session.closeReason()).isEmpty();
    }

    @Test
    void closingRequiresASessionErrorCode() {
        Session session = new Session(SessionId.random(), 1, SERVER_WINDOWS);

        assertThatIllegalArgumentException().isThrownBy(() -> session.onClosing(ErrorCode.PEER_RESET));
        assertThat(session.state()).isEqualTo(SessionState.AWAITING_HELLO);
    }

    @ParameterizedTest
    @EnumSource(
            value = SessionState.class,
            names = {"ESTABLISHED"},
            mode = EnumSource.Mode.EXCLUDE)
    void refusesStreamsUnlessEstablished(SessionState state) {
        Session session = sessionIn(state, 1);

        assertThat(session.checkAdmission(TUNNEL.id())).contains(Refusal.NOT_ESTABLISHED);
        assertThatIllegalStateException().isThrownBy(() -> session.openStream(TUNNEL.id()));
        assertThat(session.openStreamIds()).isEmpty();
    }

    @Test
    void admitsBelowTheStreamLimitAndRefusesAtIt() {
        Session session = sessionIn(SessionState.ESTABLISHED, 1_000);
        for (int i = 0; i < 999; i++) {
            open(session);
        }

        assertThat(session.checkAdmission(TUNNEL.id())).isEmpty();
        assertThat(session.openStream(TUNNEL.id())).isEqualTo(1_000);
        assertThat(session.checkAdmission(TUNNEL.id())).contains(Refusal.STREAM_LIMIT);
        assertThatIllegalStateException().isThrownBy(() -> session.openStream(TUNNEL.id()));
        assertThat(session.openStreamIds()).hasSize(1_000);
    }

    @Test
    void refusalsHaveStableMetricLabels() {
        assertThat(Refusal.NOT_ESTABLISHED.label()).isEqualTo("not_established");
        assertThat(Refusal.STREAM_LIMIT.label()).isEqualTo("stream_limit");
        assertThat(Refusal.SERVER_STREAM_LIMIT.label()).isEqualTo("server_stream_limit");
    }

    @Test
    void aStreamForAnUnknownTunnelIsABug() {
        Session session = sessionIn(SessionState.ESTABLISHED, 1);

        assertThatIllegalArgumentException().isThrownBy(() -> session.checkAdmission(TunnelId.random()));
        assertThatIllegalArgumentException().isThrownBy(() -> session.openStream(TunnelId.random()));
    }

    @Test
    void streamIdsStartAtOneAndStrictlyIncrease() {
        Session session = sessionIn(SessionState.ESTABLISHED, 3);

        assertThat(List.of(open(session), open(session), open(session))).containsExactly(1, 2, 3);
        assertThat(session.openStreamIds()).containsExactly(1, 2, 3);
    }

    @Test
    void aFrameForANeverOpenedStreamIsAProtocolError() {
        Session session = sessionIn(SessionState.ESTABLISHED, 1);
        open(session);

        assertProtocolError(() -> session.onDataReceived(2, 1));
        assertProtocolError(() -> session.onStreamFrame(2, CLOSE_RECEIVED));
        assertProtocolError(() -> session.onWindowUpdate(2, 1));
    }

    @Test
    void aCloseForAClosedStreamIsDiscarded() {
        Session session = sessionIn(SessionState.ESTABLISHED, 1);
        int id = open(session);
        session.onStreamSent(id, RST_SENT);

        assertThat(session.onStreamFrame(id, RST_RECEIVED)).isInstanceOf(StreamOutcome.Discard.class);
        assertThat(session.onStreamFrame(id, CLOSE_RECEIVED)).isInstanceOf(StreamOutcome.Discard.class);
    }

    @Test
    void delegatesClosesToTheLifecycle() {
        Session session = sessionIn(SessionState.ESTABLISHED, 1);
        int id = open(session);

        assertThat(session.onStreamFrame(id, CLOSE_RECEIVED))
                .isEqualTo(new StreamOutcome.Accept(StreamState.HALF_CLOSED_REMOTE));
        assertThat(session.onStreamFrame(id, CLOSE_RECEIVED))
                .isEqualTo(new StreamOutcome.ResetStream(ErrorCode.STREAM_CLOSED));
        assertThat(session.openStreamIds()).isEmpty();
    }

    @Test
    void aStreamClosedByBothHalfClosesLeavesTheTable() {
        Session session = sessionIn(SessionState.ESTABLISHED, 1);
        int id = open(session);

        session.onDataSent(id, 10);
        session.onStreamSent(id, CLOSE_SENT);
        assertThat(session.openStreamIds()).containsExactly(id);
        session.onStreamFrame(id, CLOSE_RECEIVED);

        assertThat(session.openStreamIds()).isEmpty();
    }

    @Test
    void reportsAStreamsStateUntilItCloses() {
        Session session = sessionIn(SessionState.ESTABLISHED, 1);
        int id = open(session);

        assertThat(session.streamState(id)).contains(StreamState.OPEN);
        session.onStreamFrame(id, CLOSE_RECEIVED);
        assertThat(session.streamState(id)).contains(StreamState.HALF_CLOSED_REMOTE);
        session.onStreamSent(id, CLOSE_SENT);
        assertThat(session.streamState(id)).isEmpty();
        assertProtocolError(() -> session.streamState(id + 1));
    }

    @Test
    void aClosedStreamsSlotIsReusedByTheNextStream() {
        Session session = sessionIn(SessionState.ESTABLISHED, 1);
        int first = open(session);
        session.onStreamFrame(first, RST_RECEIVED);

        assertThat(session.checkAdmission(TUNNEL.id())).isEmpty();
        assertThat(session.openStream(TUNNEL.id())).isEqualTo(2);
    }

    @Test
    void sendingOnAStreamThatIsNotOpenIsABug() {
        Session session = sessionIn(SessionState.ESTABLISHED, 1);
        int id = open(session);
        session.onStreamSent(id, CLOSE_SENT);

        assertThatIllegalStateException().isThrownBy(() -> session.onDataSent(id, 1));
        assertThatIllegalStateException().isThrownBy(() -> session.onDataSent(9, 1));
        assertThatIllegalStateException().isThrownBy(() -> session.onStreamSent(9, CLOSE_SENT));
    }

    @Test
    void streamEventsMustComeFromTheRightSideAndThroughTheirOwnMethod() {
        Session session = sessionIn(SessionState.ESTABLISHED, 1);
        int id = open(session);

        assertThatIllegalArgumentException().isThrownBy(() -> session.onStreamFrame(id, CLOSE_SENT));
        assertThatIllegalArgumentException().isThrownBy(() -> session.onStreamSent(id, CLOSE_RECEIVED));
        assertThatIllegalArgumentException().isThrownBy(() -> session.onStreamFrame(id, DATA_RECEIVED));
        assertThatIllegalArgumentException().isThrownBy(() -> session.onStreamFrame(id, WINDOW_UPDATE_RECEIVED));
        assertThatIllegalArgumentException().isThrownBy(() -> session.onStreamSent(id, DATA_SENT));
        assertThatIllegalArgumentException().isThrownBy(() -> session.onStreamSent(id, WINDOW_UPDATE_SENT));
        assertThat(session.streamState(id)).contains(StreamState.OPEN);
    }

    @Test
    void theControlStreamIsNotADataStream() {
        Session session = sessionIn(SessionState.ESTABLISHED, 1);

        assertThatIllegalArgumentException().isThrownBy(() -> session.onStreamFrame(0, CLOSE_RECEIVED));
        assertThatIllegalArgumentException().isThrownBy(() -> session.onDataReceived(0, 1));
        assertThatIllegalArgumentException().isThrownBy(() -> session.sendable(0));
    }

    @Test
    void openStreamsSurviveClosingForTeardown() {
        Session session = sessionIn(SessionState.ESTABLISHED, 2);
        open(session);
        open(session);

        session.onClosing(ErrorCode.SERVER_SHUTTING_DOWN);

        assertThat(session.openStreamIds()).containsExactly(1, 2);
        session.onStreamSent(1, RST_SENT);
        assertThat(session.openStreamIds()).containsExactly(2);
    }

    @Test
    void thePeakIsTheMostStreamsOpenAtOnceNotTheMostOpened() {
        Session session = sessionIn(SessionState.ESTABLISHED, 3);
        assertThat(session.peakStreams()).isZero();

        int first = open(session);
        open(session);
        session.onStreamFrame(first, RST_RECEIVED);
        open(session);

        assertThat(session.openStreamIds()).hasSize(2);
        assertThat(session.peakStreams()).isEqualTo(2);
    }

    @Test
    void describesItsFlowAndEachStreams() {
        Session session = sessionIn(SessionState.ESTABLISHED, 1);
        int id = open(session);

        assertThat(session.describeFlow()).contains("available=" + CLIENT_WINDOWS.session());
        assertThat(session.describeStream(id)).startsWith("OPEN").contains("available=" + CLIENT_WINDOWS.stream());
        session.onStreamFrame(id, RST_RECEIVED);
        assertThat(session.describeStream(id)).isEqualTo("closed");
        assertThat(sessionIn(SessionState.AWAITING_HELLO, 1).describeFlow()).contains("no flow");
    }

    // Flow control: what the server may send.

    @Test
    void theClientsHelloSetsWhatTheServerMaySend() {
        Session session = sessionIn(SessionState.ESTABLISHED, 1);
        int id = open(session);

        assertThat(session.sendable(id)).isEqualTo(CLIENT_WINDOWS.stream());
    }

    @Test
    void sendableIsTheSmallerOfTheStreamAndSessionCredit() {
        Session session = establish(new Windows(65_536, 100_000), 2);
        int first = open(session);
        int second = open(session);

        session.onDataSent(first, 65_536);

        assertThat(session.sendable(first)).isZero();
        assertThat(session.sendable(second)).isEqualTo(100_000 - 65_536);
    }

    @Test
    void sendingTakesCreditAndSendingPastItIsABugThatChangesNothing() {
        Session session = establish(new Windows(65_536, 65_536), 1);
        int id = open(session);

        session.onDataSent(id, 65_000);

        assertThat(session.sendable(id)).isEqualTo(536);
        assertThatIllegalStateException().isThrownBy(() -> session.onDataSent(id, 537));
        assertThat(session.sendable(id)).isEqualTo(536);
    }

    @Test
    void nothingIsSendableOnAStreamThisSideHalfClosedOrThatIsGone() {
        Session session = sessionIn(SessionState.ESTABLISHED, 3);
        int halfClosedLocal = open(session);
        int halfClosedRemote = open(session);
        int closed = open(session);

        session.onStreamSent(halfClosedLocal, CLOSE_SENT);
        session.onStreamFrame(halfClosedRemote, CLOSE_RECEIVED);
        session.onStreamFrame(closed, RST_RECEIVED);

        assertThat(session.sendable(halfClosedLocal)).isZero();
        assertThat(session.sendable(halfClosedRemote)).isEqualTo(CLIENT_WINDOWS.stream());
        assertThat(session.sendable(closed)).isZero();
        assertThat(session.sendable(99)).isZero();
    }

    @Test
    void streamAndSessionCreditAreReportedApartSoABlockedSenderKnowsWhatItWaitsFor() {
        Session session = establish(new Windows(65_536, 100_000), 2);
        int first = open(session);
        int second = open(session);

        session.onDataSent(first, 65_536);
        session.onDataSent(second, 30_000);

        assertThat(session.streamCredit(first)).isZero();
        assertThat(session.streamCredit(second)).isEqualTo(65_536 - 30_000);
        assertThat(session.sessionCredit()).isEqualTo(100_000 - 65_536 - 30_000);
    }

    @Test
    void aStreamThisSideHalfClosedOrThatIsGoneHasNoStreamCredit() {
        Session session = sessionIn(SessionState.ESTABLISHED, 2);
        int halfClosedLocal = open(session);
        int closed = open(session);

        session.onStreamSent(halfClosedLocal, CLOSE_SENT);
        session.onStreamFrame(closed, RST_RECEIVED);

        assertThat(session.streamCredit(halfClosedLocal)).isZero();
        assertThat(session.streamCredit(closed)).isZero();
        assertThat(session.sessionCredit()).isEqualTo(CLIENT_WINDOWS.session());
    }

    @Test
    void thereIsNoSessionCreditBeforeTheClientsHello() {
        assertThat(sessionIn(SessionState.AWAITING_HELLO, 1).sessionCredit()).isZero();
    }

    // Flow control: DATA from the client.

    @Test
    void acceptedDataIsDeliveredAndHeldAgainstBothWindows() {
        Session session = sessionIn(SessionState.ESTABLISHED, 2);
        int first = open(session);
        int second = open(session);

        assertThat(session.onDataReceived(first, SERVER_WINDOWS.stream())).isSameAs(DataOutcome.DELIVER);
        assertFlowControlError(() -> session.onDataReceived(first, 1));
        assertThat(session.onDataReceived(second, SERVER_WINDOWS.stream())).isSameAs(DataOutcome.DELIVER);
        assertThat(session.streamState(first)).contains(StreamState.OPEN);
    }

    @Test
    void dataOnAStreamThisSideHalfClosedIsStillDelivered() {
        Session session = sessionIn(SessionState.ESTABLISHED, 1);
        int id = open(session);
        session.onStreamSent(id, CLOSE_SENT);

        assertThat(session.onDataReceived(id, 100)).isSameAs(DataOutcome.DELIVER);
    }

    @Test
    void anOverrunOfTheSessionWindowIsAFlowControlError() {
        Session session = sessionIn(SessionState.ESTABLISHED, 3);
        int first = open(session);
        int second = open(session);
        int third = open(session);
        session.onDataReceived(first, SERVER_WINDOWS.stream());
        session.onDataReceived(second, SERVER_WINDOWS.stream());

        assertFlowControlError(() -> session.onDataReceived(third, 1));
    }

    @Test
    void anOverrunOfAStreamWindowLeavesTheSessionWindowUntouched() {
        Session session = sessionIn(SessionState.ESTABLISHED, 3);
        int first = open(session);
        int second = open(session);
        int third = open(session);
        session.onDataReceived(first, SERVER_WINDOWS.stream() - 1);

        assertFlowControlError(() -> session.onDataReceived(first, 2));

        assertThat(session.onDataReceived(second, SERVER_WINDOWS.stream())).isSameAs(DataOutcome.DELIVER);
        assertThat(session.onDataReceived(third, 1)).isSameAs(DataOutcome.DELIVER);
    }

    @Test
    void dataForAClosedStreamIsDiscardedAndItsSessionCreditReturned() {
        Session session = sessionIn(SessionState.ESTABLISHED, 1);
        int id = open(session);
        session.onStreamFrame(id, RST_RECEIVED);

        assertThat(session.onDataReceived(id, SESSION_THRESHOLD - 1)).isEqualTo(new DataOutcome.Discard(Credit.NONE));
        assertThat(session.onDataReceived(id, 1)).isEqualTo(new DataOutcome.Discard(new Credit(SESSION_THRESHOLD, 0)));
    }

    @Test
    void dataForAClosedStreamStillCountsAgainstTheSessionWindow() {
        Session session = sessionIn(SessionState.ESTABLISHED, 2);
        int closed = open(session);
        int open = open(session);
        session.onStreamFrame(closed, RST_RECEIVED);
        session.onDataReceived(open, SERVER_WINDOWS.stream());
        session.onDataReceived(closed, SESSION_THRESHOLD - 1);

        assertFlowControlError(() -> session.onDataReceived(closed, SERVER_WINDOWS.session()));
    }

    @Test
    void dataAfterTheClientsCloseIsAStreamErrorNotAnOverrunAndReturnsItsCredit() {
        Session session = sessionIn(SessionState.ESTABLISHED, 1);
        int id = open(session);
        session.onDataReceived(id, SERVER_WINDOWS.stream());
        session.onStreamFrame(id, CLOSE_RECEIVED);

        assertThat(session.onDataReceived(id, SESSION_THRESHOLD))
                .isEqualTo(new DataOutcome.Reset(ErrorCode.STREAM_CLOSED, new Credit(SESSION_THRESHOLD, 0)));
        assertThat(session.openStreamIds()).isEmpty();
    }

    @Test
    void dataBeforeTheClientsHelloIsABug() {
        Session session = new Session(SessionId.random(), 1, SERVER_WINDOWS);

        assertThatIllegalStateException().isThrownBy(() -> session.onDataReceived(1, 1));
    }

    // Flow control: WINDOW_UPDATE from the client.

    @Test
    void updatesAddToTheSessionAndStreamCredit() {
        Session session = establish(new Windows(65_536, 65_536), 1);
        int id = open(session);
        session.onDataSent(id, 65_536);

        assertThat(session.onWindowUpdate(0, 10)).isEqualTo(UpdateTarget.SESSION);
        assertThat(session.sendable(id)).isZero();
        assertThat(session.onWindowUpdate(id, 5)).isEqualTo(UpdateTarget.STREAM);
        assertThat(session.sendable(id)).isEqualTo(5);
    }

    @Test
    void anUpdateOnAStreamTheClientHalfClosedStillAddsCredit() {
        Session session = establish(new Windows(65_536, 1_048_576), 1);
        int id = open(session);
        session.onDataSent(id, 65_536);
        session.onStreamFrame(id, CLOSE_RECEIVED);

        assertThat(session.onWindowUpdate(id, 100)).isEqualTo(UpdateTarget.STREAM);
        assertThat(session.sendable(id)).isEqualTo(100);
    }

    @Test
    void anUpdateOnAStreamThisSideHalfClosedIsIgnored() {
        Session session = sessionIn(SessionState.ESTABLISHED, 1);
        int id = open(session);
        session.onStreamSent(id, CLOSE_SENT);

        assertThat(session.onWindowUpdate(id, Integer.MAX_VALUE)).isEqualTo(UpdateTarget.IGNORED);
        assertThat(session.streamState(id)).contains(StreamState.HALF_CLOSED_LOCAL);
    }

    @Test
    void anUpdateForAClosedStreamIsDiscarded() {
        Session session = sessionIn(SessionState.ESTABLISHED, 1);
        int id = open(session);
        session.onStreamSent(id, RST_SENT);

        assertThat(session.onWindowUpdate(id, 1)).isEqualTo(UpdateTarget.DISCARDED);
    }

    @Test
    void creditPastTheMaximumIsAFlowControlErrorThatChangesNothing() {
        Session session = establish(new Windows(65_536, 65_536), 1);
        int id = open(session);

        assertFlowControlError(() -> session.onWindowUpdate(0, Integer.MAX_VALUE));
        assertFlowControlError(() -> session.onWindowUpdate(id, Integer.MAX_VALUE));
        assertThat(session.sendable(id)).isEqualTo(65_536);
        assertThat(session.streamState(id)).contains(StreamState.OPEN);
    }

    // Flow control: credit returned as bytes are released.

    @Test
    void releasesReturnStreamAndSessionCreditAtHalfTheirWindows() {
        Session session = sessionIn(SessionState.ESTABLISHED, 1);
        int id = open(session);
        session.onDataReceived(id, SERVER_WINDOWS.stream());

        assertThat(session.onReleased(id, STREAM_THRESHOLD - 1)).isEqualTo(Credit.NONE);
        assertThat(session.onReleased(id, 1)).isEqualTo(new Credit(0, STREAM_THRESHOLD));
        assertThat(session.onReleased(id, STREAM_THRESHOLD)).isEqualTo(new Credit(SESSION_THRESHOLD, STREAM_THRESHOLD));
        assertThat(session.streamState(id)).contains(StreamState.OPEN);
        assertThat(session.onDataReceived(id, SERVER_WINDOWS.stream())).isSameAs(DataOutcome.DELIVER);
    }

    @Test
    void releasesAfterTheStreamClosedReturnSessionCreditOnly() {
        Session session = sessionIn(SessionState.ESTABLISHED, 1);
        int id = open(session);
        session.onDataReceived(id, SERVER_WINDOWS.stream());
        session.onStreamSent(id, RST_SENT);

        assertThat(session.onReleased(id, SERVER_WINDOWS.stream())).isEqualTo(new Credit(SERVER_WINDOWS.stream(), 0));
    }

    @Test
    void releasesAfterTheClientsCloseReturnSessionCreditOnly() {
        Session session = sessionIn(SessionState.ESTABLISHED, 1);
        int id = open(session);
        session.onDataReceived(id, SERVER_WINDOWS.stream());
        session.onStreamFrame(id, CLOSE_RECEIVED);

        assertThat(session.onReleased(id, SERVER_WINDOWS.stream())).isEqualTo(new Credit(SERVER_WINDOWS.stream(), 0));
        assertThat(session.streamState(id)).contains(StreamState.HALF_CLOSED_REMOTE);
    }

    @Test
    void releasingMoreThanIsHeldIsABug() {
        Session session = sessionIn(SessionState.ESTABLISHED, 1);
        int id = open(session);
        session.onDataReceived(id, 10);

        assertThatIllegalStateException().isThrownBy(() -> session.onReleased(id, 11));
        assertThat(session.onReleased(id, 10)).isEqualTo(Credit.NONE);
    }

    private static int open(Session session) {
        return session.openStream(TUNNEL.id());
    }

    private static Session establish(Windows client, int maxConcurrentStreams) {
        Session session = new Session(SessionId.random(), maxConcurrentStreams, SERVER_WINDOWS);
        session.onHello(new Hello(HELLO_FRAME.client(), List.of(), client));
        session.onRegisterReceived();
        session.onEstablished(ALICE, List.of(TUNNEL));
        return session;
    }

    private static void assertFlowControlError(Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(
                        ProtocolException.class,
                        error -> assertThat(error.code()).isEqualTo(ErrorCode.FLOW_CONTROL_ERROR));
    }

    private static void assertProtocolError(Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(
                        ProtocolException.class,
                        error -> assertThat(error.code()).isEqualTo(ErrorCode.PROTOCOL_ERROR));
    }

    private static Session sessionIn(SessionState state, int maxConcurrentStreams) {
        Session session = new Session(SessionId.random(), maxConcurrentStreams, SERVER_WINDOWS);
        if (state == SessionState.AWAITING_HELLO) {
            return session;
        }
        if (state == SessionState.CLOSING) {
            session.onClosing(ErrorCode.HANDSHAKE_TIMEOUT);
            return session;
        }
        session.onHello(HELLO_FRAME);
        if (state == SessionState.AWAITING_REGISTER) {
            return session;
        }
        session.onRegisterReceived();
        if (state == SessionState.REGISTERING) {
            return session;
        }
        session.onEstablished(ALICE, List.of(TUNNEL));
        return session;
    }

    private static Hello hello(List<String> capabilities) {
        return new Hello(HELLO_FRAME.client(), capabilities, CLIENT_WINDOWS);
    }
}
