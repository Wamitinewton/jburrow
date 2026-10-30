package io.jburrow.server.core.tunnel.adapter.netty.http;

import io.jburrow.protocol.flow.Credit;
import io.jburrow.server.core.tunnel.application.ParkingBudget;
import io.jburrow.server.core.tunnel.application.port.TunnelMetrics;
import io.jburrow.server.core.tunnel.domain.Session;
import io.netty.util.concurrent.EventExecutor;
import io.netty.util.concurrent.ScheduledFuture;
import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.LongSupplier;

/**
 * Parks the stalled responses of one session, {@code docs/phase-3-http-tunnels/ARCHITECTURE.md} §Paused readers. A
 * stream is stalled when it holds bytes it hasn't parked and none of its writes to its visitor has completed for
 * {@code park-after}. A timer ticks every half of that while any stream holds such bytes, and parks each stalled stream
 * its session's {@link ParkingBudget.Account} has room for, sending the session credit that frees. One that doesn't fit
 * is counted once and tried again on later ticks.
 *
 * <p>Parked bytes go back to the budget as they are released, which every received byte is, exactly once, when its
 * write completes or fails.
 *
 * <p>Confined to the session's event loop.
 */
final class Parking {

    private final Session session;
    private final ParkingBudget.Account account;
    private final long parkAfterNanos;
    private final LongSupplier nanoTime;
    private final EventExecutor loop;
    private final BiConsumer<Integer, Credit> sendCredit;
    private final TunnelMetrics metrics;
    private final boolean enabled;
    /** When each stream holding bytes last made progress: a write completed, or it started holding. */
    private final Map<Integer, Long> lastProgress = new HashMap<>();
    /** Stalled streams the budget had no room for, counted once until they park or make progress. */
    private final Set<Integer> refused = new HashSet<>();

    private ScheduledFuture<?> ticker;

    Parking(
            Session session,
            ParkingBudget budget,
            Duration parkAfter,
            LongSupplier nanoTime,
            EventExecutor loop,
            BiConsumer<Integer, Credit> sendCredit,
            TunnelMetrics metrics) {
        this.session = Objects.requireNonNull(session, "session");
        this.account = budget.account();
        this.enabled = budget.enabled();
        this.parkAfterNanos = parkAfter.toNanos();
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
        this.loop = Objects.requireNonNull(loop, "loop");
        this.sendCredit = Objects.requireNonNull(sendCredit, "sendCredit");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
    }

    /**
     * {@code bytes} of {@code DATA} were delivered on {@code streamId} and are held until written. A stream that held
     * nothing else unparked starts its stall clock now, and the timer starts if it isn't running.
     */
    void onHolding(int streamId, int bytes) {
        if (session.parkable(streamId) == bytes) {
            progress(streamId);
        }
        if (enabled && ticker == null) {
            long period = Math.max(1, parkAfterNanos / 2);
            ticker = loop.scheduleAtFixedRate(this::tick, period, period, TimeUnit.NANOSECONDS);
        }
    }

    /**
     * {@code bytes} received on {@code streamId} were written or dropped: releases them, and gives back to the budget
     * whatever of them was parked.
     *
     * @return the {@code WINDOW_UPDATE}s to send now
     */
    Credit onReleased(int streamId, int bytes) {
        int parkedBefore = session.parked(streamId);
        Credit credit = session.onReleased(streamId, bytes);
        account.giveBack(parkedBefore - session.parked(streamId));
        if (lastProgress.containsKey(streamId)) {
            progress(streamId);
        }
        return credit;
    }

    /** The stream's exchange ended. What it still holds is released, and given back, as its writes finish. */
    void onEnded(int streamId) {
        lastProgress.remove(streamId);
        refused.remove(streamId);
    }

    /** The session is ending: no more ticks. */
    void stop() {
        if (ticker != null) {
            ticker.cancel(false);
            ticker = null;
        }
    }

    /** Bytes this session has parked and not yet released. */
    long parked() {
        return account.held();
    }

    /** Parks every stalled stream that fits the budget, and stops ticking once no stream holds anything to park. */
    void tick() {
        long now = nanoTime.getAsLong();
        boolean holding = false;
        for (int streamId : List.copyOf(lastProgress.keySet())) {
            int parkable = session.parkable(streamId);
            if (parkable == 0) {
                continue;
            }
            holding = true;
            if (now - lastProgress.get(streamId) < parkAfterNanos) {
                continue;
            }
            if (!account.tryTake(parkable)) {
                if (refused.add(streamId)) {
                    metrics.parkRefused();
                }
                continue;
            }
            refused.remove(streamId);
            metrics.streamParked();
            sendCredit.accept(streamId, session.park(streamId));
        }
        if (!holding) {
            stop();
        }
    }

    private void progress(int streamId) {
        lastProgress.put(streamId, nanoTime.getAsLong());
        refused.remove(streamId);
    }
}
