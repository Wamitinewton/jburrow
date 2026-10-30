package io.jburrow.server.core.tunnel.domain;

import static io.jburrow.protocol.stream.StreamEvent.CLOSE_RECEIVED;
import static io.jburrow.protocol.stream.StreamEvent.CLOSE_SENT;
import static io.jburrow.protocol.stream.StreamEvent.RST_RECEIVED;
import static io.jburrow.protocol.stream.StreamEvent.RST_SENT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.jburrow.protocol.control.BodyFraming;
import io.jburrow.protocol.control.ClientInfo;
import io.jburrow.protocol.control.HeaderFields;
import io.jburrow.protocol.control.Hello;
import io.jburrow.protocol.control.HttpRequestHead;
import io.jburrow.protocol.control.HttpResponseHead;
import io.jburrow.protocol.control.SubdomainName;
import io.jburrow.protocol.control.Windows;
import io.jburrow.protocol.flow.Credit;
import io.jburrow.protocol.flow.DataOutcome;
import io.jburrow.protocol.stream.StreamOutcome;
import io.jburrow.protocol.stream.StreamState;
import io.jburrow.protocol.wire.Capability;
import io.jburrow.protocol.wire.ErrorCode;
import io.jburrow.protocol.wire.FrameType;
import io.jburrow.protocol.wire.ProtocolException;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * A session's HTTP streams ({@code docs/phase-3-http-tunnels/ARCHITECTURE.md} §Violations, §Visitor to client: demand
 * reads, §Paused readers): the exchange rules each stream's frames pass, reservations, and parking.
 */
class SessionHttpTest {

    private static final Windows CLIENT_WINDOWS = new Windows(262_144, 1_048_576);
    private static final Windows SERVER_WINDOWS = new Windows(65_536, 131_072);
    private static final ClientInfo CLIENT = new ClientInfo("jburrow-cli", "0.3.0", "linux", "x86_64");
    private static final Principal ALICE = new Principal("alice");
    private static final Tunnel HTTP_TUNNEL =
            Tunnel.http(TunnelId.random(), "default", new SubdomainName("quiet-otter"), "example.dev", "https", 443);
    private static final Tunnel TCP_TUNNEL = Tunnel.tcp(TunnelId.random(), "ssh", "example.dev", 21_037);

    private static final HttpRequestHead GET = request("GET", BodyFraming.EMPTY);
    private static final HttpRequestHead POST_10 = new HttpRequestHead(
            "POST",
            "/items",
            HeaderFields.of("host", "quiet-otter.example.dev", "content-length", "10"),
            BodyFraming.LENGTH);
    private static final HttpResponseHead OK_5 =
            new HttpResponseHead(200, HeaderFields.of("content-length", "5"), BodyFraming.LENGTH);
    private static final HttpResponseHead EARLY_HINTS = new HttpResponseHead(103, HeaderFields.of(), null);

    // The capability.

    @Test
    void headersAreAllowedOnlyOnceTheSessionHasHttp() {
        Session withHttp = establish(true, 2);
        Session without = establish(false, 2);

        assertThatCode(() -> withHttp.checkInbound(FrameType.HEADERS)).doesNotThrowAnyException();
        assertProtocolError(() -> without.checkInbound(FrameType.HEADERS));
    }

    @Test
    void anExchangeNeedsTheCapabilityAndAnHttpTunnel() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> establish(false, 2).openExchange(HTTP_TUNNEL.id(), GET));
        assertThatIllegalArgumentException().isThrownBy(() -> establish(true, 2).openExchange(TCP_TUNNEL.id(), GET));
    }

    @Test
    void anExchangeCountsAgainstTheStreamLimitLikeAVisitor() {
        Session session = establish(true, 2);
        session.openExchange(HTTP_TUNNEL.id(), GET);
        session.openStream(TCP_TUNNEL.id());

        assertThat(session.checkAdmission(HTTP_TUNNEL.id())).contains(StreamAdmission.Refusal.STREAM_LIMIT);
    }

    // The response, from the client.

    @Test
    void aResponseIsItsInformationalHeadsTheFinalHeadItsBodyAndItsEnd() {
        Session session = establish(true, 1);
        int id = session.openExchange(HTTP_TUNNEL.id(), GET);
        session.onStreamSent(id, CLOSE_SENT);

        assertThat(session.onHeadersReceived(id, EARLY_HINTS)).isInstanceOf(StreamOutcome.Accept.class);
        assertThat(session.onHeadersReceived(id, OK_5)).isInstanceOf(StreamOutcome.Accept.class);
        assertThat(session.onDataReceived(id, 5)).isSameAs(DataOutcome.DELIVER);
        assertThat(session.onStreamFrame(id, CLOSE_RECEIVED)).isEqualTo(new StreamOutcome.Accept(StreamState.CLOSED));
        assertThat(session.streamState(id)).isEmpty();
    }

    @Test
    void dataOrCloseBeforeTheFinalHeadIsAProtocolError() {
        Session session = establish(true, 2);
        int data = session.openExchange(HTTP_TUNNEL.id(), GET);
        int close = session.openExchange(HTTP_TUNNEL.id(), GET);
        session.onHeadersReceived(data, EARLY_HINTS);

        assertProtocolError(() -> session.onDataReceived(data, 1));
        assertProtocolError(() -> session.onStreamFrame(close, CLOSE_RECEIVED));
    }

    @Test
    void aSecondFinalHeadOrAnInformationalOneAfterItIsAProtocolError() {
        Session session = establish(true, 2);
        int second = session.openExchange(HTTP_TUNNEL.id(), GET);
        int late = session.openExchange(HTTP_TUNNEL.id(), GET);
        session.onHeadersReceived(second, OK_5);
        session.onHeadersReceived(late, OK_5);

        assertProtocolError(() -> session.onHeadersReceived(second, OK_5));
        assertProtocolError(() -> session.onHeadersReceived(late, EARLY_HINTS));
    }

    @Test
    void aBodyThatDisagreesWithItsContentLengthIsAProtocolError() {
        Session session = establish(true, 2);
        int longer = session.openExchange(HTTP_TUNNEL.id(), GET);
        int shorter = session.openExchange(HTTP_TUNNEL.id(), GET);
        session.onHeadersReceived(longer, OK_5);
        session.onHeadersReceived(shorter, OK_5);
        session.onDataReceived(longer, 5);
        session.onDataReceived(shorter, 4);

        assertProtocolError(() -> session.onDataReceived(longer, 1));
        assertProtocolError(() -> session.onStreamFrame(shorter, CLOSE_RECEIVED));
    }

    @Test
    void aBodyInAnswerToHeadFailsValidation() {
        Session session = establish(true, 1);
        int id = session.openExchange(HTTP_TUNNEL.id(), request("HEAD", BodyFraming.EMPTY));

        assertThatThrownBy(() -> session.onHeadersReceived(id, OK_5))
                .isInstanceOfSatisfying(
                        ProtocolException.class,
                        error -> assertThat(error.code()).isEqualTo(ErrorCode.VALIDATION_FAILED));
    }

    @Test
    void headersOnATcpStreamOrANeverOpenedOneAreProtocolErrors() {
        Session session = establish(true, 1);
        int visitor = session.openStream(TCP_TUNNEL.id());

        assertProtocolError(() -> session.onHeadersReceived(visitor, OK_5));
        assertProtocolError(() -> session.onHeadersReceived(visitor + 2, OK_5));
    }

    @Test
    void headersAfterTheClientsOwnCloseResetTheStreamAndOnAClosedOneAreDiscarded() {
        Session session = establish(true, 2);
        int halfClosed = session.openExchange(HTTP_TUNNEL.id(), GET);
        int reset = session.openExchange(HTTP_TUNNEL.id(), GET);
        session.onHeadersReceived(halfClosed, new HttpResponseHead(204, HeaderFields.of(), BodyFraming.EMPTY));
        session.onStreamFrame(halfClosed, CLOSE_RECEIVED);
        session.onStreamFrame(reset, RST_RECEIVED);

        assertThat(session.onHeadersReceived(halfClosed, OK_5))
                .isEqualTo(new StreamOutcome.ResetStream(ErrorCode.STREAM_CLOSED));
        assertThat(session.onHeadersReceived(reset, OK_5)).isInstanceOf(StreamOutcome.Discard.class);
    }

    // The request, from this side, against reservations.

    @Test
    void aReservationTakesStreamAndSessionCreditAndARefundGivesItBack() {
        Session session = establish(true, 2);
        int id = session.openExchange(HTTP_TUNNEL.id(), POST_10);
        long sessionCredit = session.sessionCredit();

        assertThat(session.reserve(id, 16_384)).isEqualTo(16_384);
        assertThat(session.sessionCredit()).isEqualTo(sessionCredit - 16_384);
        assertThat(session.streamCredit(id)).isEqualTo(CLIENT_WINDOWS.stream() - 16_384);

        session.onReservedDataSent(id, 10);
        session.refund(id, 16_384 - 10);

        assertThat(session.sessionCredit()).isEqualTo(sessionCredit - 10);
        assertThat(session.streamCredit(id)).isEqualTo(CLIENT_WINDOWS.stream() - 10);
        assertThatIllegalStateException().isThrownBy(() -> session.refund(id, 1));
    }

    @Test
    void aReservationIsLimitedByCreditAndIsNothingOnAStreamThisSideHalfClosed() {
        Session session = establish(true, 2);
        int id = session.openExchange(HTTP_TUNNEL.id(), GET);
        int closed = session.openExchange(HTTP_TUNNEL.id(), GET);
        session.onStreamSent(closed, CLOSE_SENT);

        assertThat(session.reserve(id, Integer.MAX_VALUE)).isEqualTo(CLIENT_WINDOWS.stream());
        assertThat(session.reserve(id, 1)).isZero();
        assertThat(session.reserve(closed, 1)).isZero();
    }

    @Test
    void aStreamThatClosesGivesBackItsReservationSoOtherStreamsCanSend() {
        Session session = establish(true, 2);
        int id = session.openExchange(HTTP_TUNNEL.id(), POST_10);
        long sessionCredit = session.sessionCredit();
        session.reserve(id, 16_384);

        session.onStreamSent(id, RST_SENT);

        assertThat(session.sessionCredit()).isEqualTo(sessionCredit);
        assertThatCode(() -> session.refund(id, 16_384)).doesNotThrowAnyException();
    }

    @Test
    void requestBodyPastItsContentLengthOrEndedShortIsABug() {
        Session session = establish(true, 2);
        int longer = session.openExchange(HTTP_TUNNEL.id(), POST_10);
        int shorter = session.openExchange(HTTP_TUNNEL.id(), POST_10);
        session.reserve(longer, 16_384);
        session.reserve(shorter, 16_384);
        session.onReservedDataSent(longer, 10);
        session.onReservedDataSent(shorter, 9);

        assertThatIllegalStateException().isThrownBy(() -> session.onReservedDataSent(longer, 1));
        assertThatIllegalStateException().isThrownBy(() -> session.onStreamSent(shorter, CLOSE_SENT));
    }

    // Parking.

    @Test
    void parkingReturnsSessionCreditAtOnceAndStreamCreditOnlyAsBytesAreWritten() {
        Session session = establish(true, 1);
        int id = session.openExchange(HTTP_TUNNEL.id(), GET);
        session.onHeadersReceived(id, chunked());
        session.onDataReceived(id, SERVER_WINDOWS.stream());

        assertThat(session.parkable(id)).isEqualTo(SERVER_WINDOWS.stream());
        assertThat(session.park(id)).isEqualTo(new Credit(SERVER_WINDOWS.stream(), 0));
        assertThat(session.parkable(id)).isZero();
        assertThat(session.parked(id)).isEqualTo(SERVER_WINDOWS.stream());
        assertThat(session.park(id)).isEqualTo(Credit.NONE);

        assertThat(session.onReleased(id, SERVER_WINDOWS.stream())).isEqualTo(new Credit(0, SERVER_WINDOWS.stream()));
        assertThat(session.parked(id)).isZero();
    }

    @Test
    void aStreamThatClosesWithBytesParkedReturnsNoCreditTwiceForThem() {
        Session session = establish(true, 1);
        int id = session.openExchange(HTTP_TUNNEL.id(), GET);
        session.onHeadersReceived(id, chunked());
        session.onDataReceived(id, SERVER_WINDOWS.stream());
        session.park(id);

        session.onStreamFrame(id, RST_RECEIVED);

        assertThat(session.parkable(id)).isZero();
        assertThat(session.parked(id)).isEqualTo(SERVER_WINDOWS.stream());
        assertThat(session.onReleased(id, SERVER_WINDOWS.stream())).isEqualTo(Credit.NONE);
        assertThat(session.parked(id)).isZero();
        assertThatIllegalStateException().isThrownBy(() -> session.onReleased(id, 1));
    }

    private static HttpResponseHead chunked() {
        return new HttpResponseHead(200, HeaderFields.of(), BodyFraming.CHUNKED);
    }

    private static HttpRequestHead request(String method, BodyFraming body) {
        return new HttpRequestHead(method, "/", HeaderFields.of("host", "quiet-otter.example.dev"), body);
    }

    private static Session establish(boolean http, int maxConcurrentStreams) {
        Session session =
                new Session(SessionId.random(), maxConcurrentStreams, SERVER_WINDOWS, Set.of(Capability.HTTP));
        List<String> offered = http ? List.of(Capability.HTTP.wireName()) : List.of();
        session.onHello(new Hello(CLIENT, offered, CLIENT_WINDOWS));
        session.onRegisterReceived();
        session.onEstablished(ALICE, List.of(HTTP_TUNNEL, TCP_TUNNEL));
        return session;
    }

    private static void assertProtocolError(Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(
                        ProtocolException.class,
                        error -> assertThat(error.code()).isEqualTo(ErrorCode.PROTOCOL_ERROR));
    }
}
