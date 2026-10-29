package io.jburrow.server.core.tunnel.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.jburrow.protocol.flow.SessionFlow;
import io.jburrow.protocol.flow.StreamFlow;
import io.jburrow.protocol.stream.StreamEvent;
import io.jburrow.protocol.stream.StreamOutcome;
import io.jburrow.protocol.stream.StreamState;
import io.jburrow.protocol.wire.ErrorCode;
import io.jburrow.protocol.wire.ProtocolException;
import org.junit.jupiter.api.Test;

class StreamTableTest {

    private final SessionFlow flow = new SessionFlow(65_536, 131_072, 262_144, 1_048_576);

    @Test
    void anOpenedEntryCarriesItsFlowWithTheSessionsWindows() {
        StreamTable table = new StreamTable(2);
        StreamFlow streamFlow = flow.openStream();

        int id = table.allocate(streamFlow);

        StreamTable.Entry entry = table.lookup(id).orElseThrow();
        assertThat(entry.flow()).isSameAs(streamFlow);
        assertThat(entry.flow().send().available()).isEqualTo(262_144);
        assertThat(entry.flow().receive().size()).isEqualTo(65_536);
        assertThat(entry.lifecycle().state()).isEqualTo(StreamState.OPEN);
        assertThat(table.find(id)).containsSame(entry);
    }

    @Test
    void closingAStreamRemovesItsEntryAndFlow() {
        StreamTable table = new StreamTable(1);
        int id = table.allocate(flow.openStream());

        table.onReceived(id, StreamEvent.CLOSE_RECEIVED);
        assertThat(table.find(id)).isPresent();
        table.onSent(id, StreamEvent.CLOSE_SENT);

        assertThat(table.lookup(id)).isEmpty();
        assertThat(table.find(id)).isEmpty();
        assertThat(table.size()).isZero();
        assertThat(table.isFull()).isFalse();
    }

    @Test
    void thePeakSizeOutlivesTheStreamsThatReachedIt() {
        StreamTable table = new StreamTable(3);
        int first = table.allocate(flow.openStream());
        int second = table.allocate(flow.openStream());
        table.onReceived(first, StreamEvent.RST_RECEIVED);
        table.onReceived(second, StreamEvent.RST_RECEIVED);
        table.allocate(flow.openStream());

        assertThat(table.size()).isOne();
        assertThat(table.peakSize()).isEqualTo(2);
    }

    @Test
    void framesForAClosedStreamAreDiscarded() {
        StreamTable table = new StreamTable(1);
        int id = table.allocate(flow.openStream());
        table.onReceived(id, StreamEvent.RST_RECEIVED);

        assertThat(table.onReceived(id, StreamEvent.DATA_RECEIVED)).isInstanceOf(StreamOutcome.Discard.class);
    }

    @Test
    void lookupOfANeverOpenedIdIsAProtocolErrorButFindIsEmpty() {
        StreamTable table = new StreamTable(1);
        table.allocate(flow.openStream());

        assertThatThrownBy(() -> table.lookup(2))
                .isInstanceOfSatisfying(
                        ProtocolException.class,
                        error -> assertThat(error.code()).isEqualTo(ErrorCode.PROTOCOL_ERROR));
        assertThat(table.find(2)).isEmpty();
        assertThatIllegalArgumentException().isThrownBy(() -> table.find(0));
    }

    @Test
    void allocatingPastCapacityIsABug() {
        StreamTable table = new StreamTable(1);
        table.allocate(flow.openStream());

        assertThat(table.isFull()).isTrue();
        assertThatIllegalStateException().isThrownBy(() -> table.allocate(flow.openStream()));
        assertThat(table.openIds()).containsExactly(1);
    }

    @Test
    void sendingOnAStreamThatIsNotOpenIsABug() {
        StreamTable table = new StreamTable(1);

        assertThatIllegalStateException().isThrownBy(() -> table.onSent(1, StreamEvent.CLOSE_SENT));
    }
}
