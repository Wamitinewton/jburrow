package io.jburrow.server.core.tunnel.adapter.netty.http;

import static io.jburrow.server.core.tunnel.adapter.netty.http.ExchangeGate.DEMAND;
import static org.assertj.core.api.Assertions.assertThat;

import io.jburrow.protocol.control.Windows;
import io.jburrow.protocol.wire.StreamIds;
import io.jburrow.server.core.tunnel.adapter.netty.http.ExchangeGate.Wait;
import io.jburrow.server.core.tunnel.application.RecordingTunnelMetrics;
import io.jburrow.server.core.tunnel.domain.Session;
import io.netty.buffer.Unpooled;
import io.netty.channel.WriteBufferWaterMark;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * {@code docs/phase-3-http-tunnels/ARCHITECTURE.md} §Visitor to client: demand reads: grants as reservations of at most
 * one demand, the waits, the line, turns, and resumes as far as the credit covers demands.
 */
class ExchangeGateTest {

    /** Four demands of session credit, and a stream window of four demands. */
    private static final Windows CLIENT = new Windows(4 * DEMAND, 4 * DEMAND);

    private static final int HIGH_WATERMARK = 64 * 1024;

    private final RecordingTunnelMetrics metrics = new RecordingTunnelMetrics();
    private final EmbeddedChannel sessionChannel = new EmbeddedChannel();
    private final List<Integer> resumed = new ArrayList<>();
    private Session session;
    private ExchangeGate gate;

    @AfterEach
    void close() {
        sessionChannel.finishAndReleaseAll();
    }

    @Test
    void aGrantReservesOneDemandOfStreamAndSessionCredit() {
        start(CLIENT);
        Reader reader = reader();

        assertThat(gate.grant(reader)).isEqualTo(DEMAND);

        assertThat(session.sessionCredit()).isEqualTo(3 * DEMAND);
        assertThat(session.streamCredit(reader.streamId)).isEqualTo(3 * DEMAND);
        assertThat(gate.waiting(reader)).isNull();
    }

    @Test
    void aGrantIsNoMoreThanTheCreditOrTheHeadroomLeft() {
        start(CLIENT);
        Reader first = reader();
        spend(first, 3 * DEMAND + DEMAND / 2);
        Reader second = reader();

        assertThat(gate.grant(second)).isEqualTo(DEMAND / 2);

        session.onWindowUpdate(StreamIds.CONTROL, 4 * DEMAND);
        fillOutboundBuffer(HIGH_WATERMARK - 1_000);
        long headroom = sessionChannel.bytesBeforeUnwritable();
        assertThat(headroom).isBetween(1L, (long) DEMAND);
        assertThat(gate.grant(second)).isEqualTo(headroom);
    }

    @Test
    void unusedGrantGoesBackWithARefundForTheNextReader() {
        start(CLIENT);
        Reader first = reader();
        Reader second = reader();
        for (int i = 0; i < 4; i++) {
            gate.grant(first);
        }
        assertThat(gate.grant(second)).isZero();

        session.refund(first.streamId, DEMAND - 10);

        assertThat(gate.grant(second)).isEqualTo(DEMAND - 10);
    }

    @Test
    void aReaderShortOfItsStreamCreditWaitsForItsStreamOnly() {
        start(new Windows(4 * DEMAND, 8 * DEMAND));
        Reader reader = reader();
        spend(reader, 4 * DEMAND);

        assertThat(gate.grant(reader)).isZero();
        assertThat(gate.waiting(reader)).isEqualTo(Wait.STREAM);
        assertThat(gate.line()).isEmpty();

        gate.onSessionCredit();
        assertThat(resumed).isEmpty();

        session.onWindowUpdate(reader.streamId, DEMAND);
        gate.onStreamCredit(reader);
        assertThat(resumed).containsExactly(reader.streamId);
        assertThat(metrics.events()).containsOnlyOnce("flowBlocked STREAM");
    }

    @Test
    void readersShortOfSessionCreditJoinTheLineAndAreResumedInOrderAsFarAsTheCreditGoes() {
        start(CLIENT);
        Reader spender = reader();
        spend(spender, 4 * DEMAND);
        Reader first = reader();
        Reader second = reader();
        Reader third = reader();
        for (Reader reader : List.of(first, second, third)) {
            assertThat(gate.grant(reader)).isZero();
            assertThat(gate.waiting(reader)).isEqualTo(Wait.SESSION);
        }
        first.grantOnResume = true;
        second.grantOnResume = true;
        third.grantOnResume = true;

        session.onWindowUpdate(StreamIds.CONTROL, 2 * DEMAND);
        gate.onSessionCredit();

        assertThat(resumed).containsExactly(first.streamId, second.streamId);
        assertThat(first.granted).isEqualTo(DEMAND);
        assertThat(second.granted).isEqualTo(DEMAND);
        assertThat(gate.line()).containsExactly(third);
        assertThat(gate.grant(third)).isZero();
        assertThat(gate.line()).containsExactly(third);
    }

    @Test
    void aReaderKeepsItsPlaceInLineThroughAResumeThatFindsNoCredit() {
        start(CLIENT);
        Reader spender = reader();
        spend(spender, 4 * DEMAND);
        Reader first = reader();
        Reader second = reader();
        gate.grant(first);
        gate.grant(second);

        gate.grant(first);

        assertThat(gate.line()).containsExactly(first, second);
    }

    @Test
    void aReaderWhoseReadIsDoneTakesItsTurnBehindThoseWaiting() {
        start(CLIENT);
        Reader busy = reader();
        spend(busy, 3 * DEMAND);
        assertThat(gate.grant(busy)).isEqualTo(DEMAND);
        Reader waiting = reader();
        assertThat(gate.grant(waiting)).isZero();
        waiting.grantOnResume = true;
        busy.grantOnResume = true;

        session.refund(busy.streamId, DEMAND);
        boolean mayGoOn = gate.turnTaken(busy);

        assertThat(mayGoOn).isFalse();
        assertThat(resumed).containsExactly(waiting.streamId);
        assertThat(waiting.granted).isEqualTo(DEMAND);
        assertThat(gate.line()).containsExactly(busy);
        assertThat(gate.waiting(busy)).isEqualTo(Wait.TURN);
    }

    @Test
    void aReaderWithNobodyInLineGoesOnAtOnce() {
        start(CLIENT);
        Reader reader = reader();
        gate.grant(reader);

        assertThat(gate.turnTaken(reader)).isTrue();
        assertThat(resumed).isEmpty();
    }

    @Test
    void aFullOutboundBufferHoldsReadersInLineUntilTheSessionChannelIsWritable() {
        start(CLIENT);
        Reader reader = reader();
        reader.grantOnResume = true;
        fillOutboundBuffer(HIGH_WATERMARK);

        assertThat(gate.grant(reader)).isZero();
        assertThat(gate.waiting(reader)).isEqualTo(Wait.HEADROOM);
        gate.onSessionWritable(false);
        assertThat(resumed).isEmpty();

        sessionChannel.flushOutbound();
        sessionChannel.releaseOutbound();
        gate.onSessionWritable(true);

        assertThat(resumed).containsExactly(reader.streamId);
        assertThat(reader.granted).isEqualTo(DEMAND);
        assertThat(metrics.events()).contains("backpressurePaused INBOUND");
    }

    @Test
    void aRemovedReaderLeavesTheLine() {
        start(CLIENT);
        Reader spender = reader();
        spend(spender, 4 * DEMAND);
        Reader gone = reader();
        Reader next = reader();
        gate.grant(gone);
        gate.grant(next);

        gate.onRemoved(gone);
        session.onWindowUpdate(StreamIds.CONTROL, DEMAND);
        gate.onSessionCredit();

        assertThat(gate.waiting(gone)).isNull();
        assertThat(resumed).containsExactly(next.streamId);
    }

    private void start(Windows client) {
        sessionChannel.config().setWriteBufferWaterMark(new WriteBufferWaterMark(HIGH_WATERMARK / 2, HIGH_WATERMARK));
        session = HttpSessions.established(client);
        gate = new ExchangeGate(session, sessionChannel, metrics);
    }

    private Reader reader() {
        return new Reader(session.openExchange(HttpSessions.TUNNEL.id(), HttpSessions.UPLOAD));
    }

    /** Sends {@code bytes} on the reader's stream, a demand at a time, so its credit and the session's are spent. */
    private void spend(Reader reader, int bytes) {
        for (int left = bytes; left > 0; ) {
            int granted = gate.grant(reader);
            int sent = Math.min(granted, left);
            session.onReservedDataSent(reader.streamId, sent);
            session.refund(reader.streamId, granted - sent);
            left -= sent;
        }
    }

    private void fillOutboundBuffer(int bytes) {
        sessionChannel.write(Unpooled.wrappedBuffer(new byte[bytes]));
    }

    private final class Reader implements ExchangeGate.Reader {

        private final int streamId;
        private boolean grantOnResume;
        private int granted;

        Reader(int streamId) {
            this.streamId = streamId;
        }

        @Override
        public int streamId() {
            return streamId;
        }

        @Override
        public void resume() {
            resumed.add(streamId);
            if (grantOnResume) {
                granted += gate.grant(this);
            }
        }
    }
}
