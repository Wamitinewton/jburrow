package io.jburrow.server.core.tunnel.domain;

import static io.jburrow.protocol.stream.StreamEvent.CLOSE_RECEIVED;
import static io.jburrow.protocol.stream.StreamEvent.CLOSE_SENT;
import static io.jburrow.protocol.stream.StreamEvent.DATA_RECEIVED;
import static io.jburrow.protocol.stream.StreamEvent.DATA_SENT;
import static io.jburrow.protocol.stream.StreamEvent.RST_RECEIVED;
import static io.jburrow.protocol.stream.StreamEvent.RST_SENT;
import static io.jburrow.protocol.wire.FrameType.CLOSE;
import static io.jburrow.protocol.wire.FrameType.DATA;
import static io.jburrow.protocol.wire.FrameType.ERROR;
import static io.jburrow.protocol.wire.FrameType.HELLO;
import static io.jburrow.protocol.wire.FrameType.PING;
import static io.jburrow.protocol.wire.FrameType.PONG;
import static io.jburrow.protocol.wire.FrameType.REGISTER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import io.jburrow.protocol.control.ClientInfo;
import io.jburrow.protocol.control.Hello;
import io.jburrow.protocol.control.TunnelKind;
import io.jburrow.protocol.stream.StreamOutcome;
import io.jburrow.protocol.stream.StreamState;
import io.jburrow.protocol.wire.ErrorCode;
import io.jburrow.protocol.wire.FrameType;
import io.jburrow.protocol.wire.ProtocolException;
import io.jburrow.server.core.tunnel.domain.StreamAdmission.Admitted;
import io.jburrow.server.core.tunnel.domain.StreamAdmission.Refusal;
import io.jburrow.server.core.tunnel.domain.StreamAdmission.Refused;
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

    private static final Hello HELLO_FRAME =
            new Hello(new ClientInfo("jburrow-cli", "0.2.0", "linux", "x86_64"), List.of());
    private static final Principal ALICE = new Principal("alice");
    private static final Tunnel TUNNEL = new Tunnel(TunnelId.random(), "default", TunnelKind.TCP, "localhost", 21_037);

    /** {@code docs/protocol.md} §8.2, the frames a client may send in each state. */
    private static final Map<SessionState, Set<FrameType>> ALLOWED = Map.of(
            SessionState.AWAITING_HELLO, EnumSet.of(HELLO, ERROR),
            SessionState.AWAITING_REGISTER, EnumSet.of(REGISTER, PING, PONG, ERROR),
            SessionState.REGISTERING, EnumSet.of(PING, PONG, ERROR),
            SessionState.ESTABLISHED, EnumSet.of(DATA, CLOSE, PING, PONG, ERROR),
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
        Session session = new Session(SessionId.random(), 1);
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
        Session session = new Session(SessionId.random(), 1);

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
        Session session = new Session(SessionId.random(), 1);

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

        assertThat(session.openStream(TUNNEL.id())).isEqualTo(new Refused(Refusal.NOT_ESTABLISHED));
        assertThat(session.openStreamIds()).isEmpty();
    }

    @Test
    void refusesAStreamAtCapacity() {
        Session session = sessionIn(SessionState.ESTABLISHED, 1);
        session.openStream(TUNNEL.id());

        assertThat(session.openStream(TUNNEL.id())).isEqualTo(new Refused(Refusal.STREAM_LIMIT));
        assertThat(Refusal.STREAM_LIMIT.label()).isEqualTo("stream_limit");
        assertThat(Refusal.NOT_ESTABLISHED.label()).isEqualTo("not_established");
    }

    @Test
    void aStreamForAnUnknownTunnelIsABug() {
        Session session = sessionIn(SessionState.ESTABLISHED, 1);

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

        assertProtocolError(() -> session.onStreamFrame(2, DATA_RECEIVED));
    }

    @Test
    void aFrameForAClosedStreamIsDiscarded() {
        Session session = sessionIn(SessionState.ESTABLISHED, 1);
        int id = open(session);
        session.onStreamSent(id, RST_SENT);

        assertThat(session.onStreamFrame(id, DATA_RECEIVED)).isInstanceOf(StreamOutcome.Discard.class);
        assertThat(session.onStreamFrame(id, RST_RECEIVED)).isInstanceOf(StreamOutcome.Discard.class);
    }

    @Test
    void delegatesStreamEventsToTheLifecycle() {
        Session session = sessionIn(SessionState.ESTABLISHED, 1);
        int id = open(session);

        assertThat(session.onStreamFrame(id, DATA_RECEIVED)).isEqualTo(new StreamOutcome.Accept(StreamState.OPEN));
        assertThat(session.onStreamFrame(id, CLOSE_RECEIVED))
                .isEqualTo(new StreamOutcome.Accept(StreamState.HALF_CLOSED_REMOTE));
        assertThat(session.onStreamFrame(id, DATA_RECEIVED))
                .isEqualTo(new StreamOutcome.ResetStream(ErrorCode.STREAM_CLOSED));
        assertThat(session.openStreamIds()).isEmpty();
    }

    @Test
    void aStreamClosedByBothHalfClosesLeavesTheTable() {
        Session session = sessionIn(SessionState.ESTABLISHED, 1);
        int id = open(session);

        session.onStreamSent(id, DATA_SENT);
        session.onStreamSent(id, CLOSE_SENT);
        assertThat(session.openStreamIds()).containsExactly(id);
        session.onStreamFrame(id, CLOSE_RECEIVED);

        assertThat(session.openStreamIds()).isEmpty();
    }

    @Test
    void aClosedStreamsSlotIsReusedByTheNextStream() {
        Session session = sessionIn(SessionState.ESTABLISHED, 1);
        int first = open(session);
        session.onStreamFrame(first, RST_RECEIVED);

        StreamAdmission next = session.openStream(TUNNEL.id());

        assertThat(next).isEqualTo(new Admitted(2));
    }

    @Test
    void sendingOnAStreamThatIsNotOpenIsABug() {
        Session session = sessionIn(SessionState.ESTABLISHED, 1);
        int id = open(session);
        session.onStreamSent(id, CLOSE_SENT);

        assertThatIllegalStateException().isThrownBy(() -> session.onStreamSent(id, DATA_SENT));
        assertThatIllegalStateException().isThrownBy(() -> session.onStreamSent(9, DATA_SENT));
    }

    @Test
    void streamEventsMustComeFromTheRightSide() {
        Session session = sessionIn(SessionState.ESTABLISHED, 1);
        int id = open(session);

        assertThatIllegalArgumentException().isThrownBy(() -> session.onStreamFrame(id, DATA_SENT));
        assertThatIllegalArgumentException().isThrownBy(() -> session.onStreamSent(id, DATA_RECEIVED));
    }

    @Test
    void theControlStreamIsNotADataStream() {
        Session session = sessionIn(SessionState.ESTABLISHED, 1);

        assertThatIllegalArgumentException().isThrownBy(() -> session.onStreamFrame(0, DATA_RECEIVED));
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

    private static int open(Session session) {
        return ((Admitted) session.openStream(TUNNEL.id())).streamId();
    }

    private static void assertProtocolError(Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(
                        ProtocolException.class,
                        error -> assertThat(error.code()).isEqualTo(ErrorCode.PROTOCOL_ERROR));
    }

    private static Session sessionIn(SessionState state, int maxConcurrentStreams) {
        Session session = new Session(SessionId.random(), maxConcurrentStreams);
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
}
