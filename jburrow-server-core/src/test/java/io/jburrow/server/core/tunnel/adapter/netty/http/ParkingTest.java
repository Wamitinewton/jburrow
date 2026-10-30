package io.jburrow.server.core.tunnel.adapter.netty.http;

import static org.assertj.core.api.Assertions.assertThat;

import io.jburrow.protocol.control.BodyFraming;
import io.jburrow.protocol.control.HeaderFields;
import io.jburrow.protocol.control.HttpResponseHead;
import io.jburrow.protocol.control.Windows;
import io.jburrow.protocol.flow.Credit;
import io.jburrow.protocol.stream.StreamEvent;
import io.jburrow.server.core.tunnel.application.ParkingBudget;
import io.jburrow.server.core.tunnel.application.RecordingTunnelMetrics;
import io.jburrow.server.core.tunnel.domain.Session;
import io.netty.channel.embedded.EmbeddedChannel;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * {@code docs/phase-3-http-tunnels/ARCHITECTURE.md} §Paused readers: stall detection, both budgets, a refusal counted
 * once and retried, and parked bytes given back as they are released, also after their stream ends.
 */
class ParkingTest {

    private static final Duration PARK_AFTER = Duration.ofSeconds(1);
    private static final int WINDOW = HttpSessions.SERVER_WINDOWS.stream();
    private static final HttpResponseHead STREAMING = new HttpResponseHead(200, HeaderFields.of(), BodyFraming.CHUNKED);

    private final AtomicLong now = new AtomicLong();
    private final RecordingTunnelMetrics metrics = new RecordingTunnelMetrics();
    private final EmbeddedChannel loop = new EmbeddedChannel();
    private final List<Credit> sent = new ArrayList<>();
    private final Session session = HttpSessions.established(new Windows(256 * 1024, 1024 * 1024));

    @AfterEach
    void close() {
        loop.finishAndReleaseAll();
    }

    @Test
    void aStreamThatHasCompletedNoWriteForParkAfterIsParkedAndItsSessionCreditSent() {
        ParkingBudget budget = new ParkingBudget(16 * WINDOW, 4 * WINDOW);
        Parking parking = parking(budget);
        int id = respond(parking, WINDOW);

        now.addAndGet(PARK_AFTER.toNanos() - 1);
        parking.tick();
        assertThat(sent).isEmpty();

        now.addAndGet(1);
        parking.tick();

        assertThat(sent).containsExactly(new Credit(WINDOW, 0));
        assertThat(session.parked(id)).isEqualTo(WINDOW);
        assertThat(budget.parked()).isEqualTo(WINDOW);
        assertThat(metrics.events()).containsOnlyOnce("streamParked");
    }

    @Test
    void aCompletedWriteRestartsTheStallClock() {
        Parking parking = parking(new ParkingBudget(16 * WINDOW, 4 * WINDOW));
        int id = respond(parking, WINDOW);
        now.addAndGet(PARK_AFTER.toNanos() - 1);

        parking.onReleased(id, 1);
        now.addAndGet(PARK_AFTER.toNanos() - 1);
        parking.tick();

        assertThat(session.parked(id)).isZero();
    }

    @Test
    void parkedBytesGoBackToTheBudgetAsTheyAreReleased() {
        ParkingBudget budget = new ParkingBudget(16 * WINDOW, 4 * WINDOW);
        Parking parking = parking(budget);
        int id = respond(parking, WINDOW);
        stall(parking);

        parking.onReleased(id, WINDOW / 4);

        assertThat(budget.parked()).isEqualTo(WINDOW - WINDOW / 4);
        assertThat(parking.parked()).isEqualTo(WINDOW - WINDOW / 4);
    }

    @Test
    void aStreamThatEndsWithBytesParkedStillGivesThemBackAsItsWritesFinish() {
        ParkingBudget budget = new ParkingBudget(16 * WINDOW, 4 * WINDOW);
        Parking parking = parking(budget);
        int id = respond(parking, WINDOW);
        stall(parking);

        session.onStreamSent(id, StreamEvent.RST_SENT);
        parking.onEnded(id);
        Credit credit = parking.onReleased(id, WINDOW);

        assertThat(credit).isEqualTo(Credit.NONE);
        assertThat(budget.parked()).isZero();
    }

    @Test
    void aSessionParksNoMoreThanItsShareAndTheServerNoMoreThanItsTotal() {
        int half = WINDOW / 2;
        ParkingBudget budget = new ParkingBudget(3L * half, 2L * half);
        Parking first = parking(budget);
        int a = respond(first, half);
        int b = respond(first, half);
        int c = respond(first, half);

        stall(first);

        assertThat(List.of(session.parked(a), session.parked(b), session.parked(c)))
                .containsExactly(half, half, 0);
        assertThat(budget.parked()).isEqualTo(2L * half);

        Session other = HttpSessions.established(new Windows(256 * 1024, 1024 * 1024));
        Parking second =
                new Parking(other, budget, PARK_AFTER, now::get, loop.eventLoop(), (id, credit) -> {}, metrics);
        int d = respondOn(other, second, half);
        int e = respondOn(other, second, half);
        stall(second);

        assertThat(other.parked(d) + other.parked(e)).isEqualTo(half);
        assertThat(budget.parked()).isEqualTo(3L * half);
    }

    @Test
    void aStallTheBudgetCantTakeIsCountedOnceAndParkedWhenRoomAppears() {
        ParkingBudget budget = new ParkingBudget(WINDOW, WINDOW);
        Parking parking = parking(budget);
        int parked = respond(parking, WINDOW);
        int refused = respond(parking, WINDOW);
        stall(parking);
        parking.tick();
        parking.tick();

        assertThat(session.parked(refused)).isZero();
        assertThat(metrics.events()).containsOnlyOnce("parkRefused");

        parking.onReleased(parked, WINDOW);
        parking.tick();

        assertThat(session.parked(refused)).isEqualTo(WINDOW);
    }

    @Test
    void theTimerRunsOnlyWhileAStreamHoldsSomethingToParkAndNotAtAllWithParkingOff() {
        Parking parking = parking(new ParkingBudget(16 * WINDOW, 4 * WINDOW));
        int id = respond(parking, WINDOW);
        assertThat(loop.runScheduledPendingTasks()).isPositive();

        parking.onReleased(id, WINDOW);
        parking.tick();
        assertThat(loop.runScheduledPendingTasks()).isEqualTo(-1);

        Parking off = parking(new ParkingBudget(0, 0));
        respond(off, WINDOW);
        assertThat(loop.runScheduledPendingTasks()).isEqualTo(-1);
    }

    private Parking parking(ParkingBudget budget) {
        return new Parking(
                session, budget, PARK_AFTER, now::get, loop.eventLoop(), (id, credit) -> sent.add(credit), metrics);
    }

    /** An exchange whose response has delivered {@code bytes} that wait for their visitor. */
    private int respond(Parking parking, int bytes) {
        return respondOn(session, parking, bytes);
    }

    private static int respondOn(Session session, Parking parking, int bytes) {
        int id = session.openExchange(HttpSessions.TUNNEL.id(), HttpSessions.GET);
        session.onStreamSent(id, StreamEvent.CLOSE_SENT);
        session.onHeadersReceived(id, STREAMING);
        session.onDataReceived(id, bytes);
        parking.onHolding(id, bytes);
        return id;
    }

    private void stall(Parking parking) {
        now.addAndGet(PARK_AFTER.toNanos());
        parking.tick();
    }
}
