package io.jburrow.client.tunnel.adapter.netty;

import io.jburrow.client.ClientSettings;
import io.jburrow.client.TunnelHandle;
import io.jburrow.client.TunnelStartException;
import io.jburrow.client.TunnelStartException.Kind;
import io.jburrow.client.tunnel.application.port.TunnelEvents;
import io.jburrow.client.tunnel.domain.ReconnectSchedule;
import io.jburrow.client.tunnel.domain.Resumption;
import io.jburrow.client.tunnel.domain.SessionEnd;
import io.jburrow.client.tunnel.domain.SessionOutcome;
import io.jburrow.client.tunnel.domain.SessionOutcome.Wait;
import io.jburrow.client.tunnel.domain.TunnelClosed;
import io.jburrow.protocol.control.GoAway;
import io.jburrow.protocol.control.RegisteredTunnel;
import io.netty.channel.EventLoop;
import io.netty.util.concurrent.ScheduledFuture;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs one session after another, each a {@link ControlConnection}: ARCHITECTURE.md §Reconnecting, phase 4. When a
 * session ends or an attempt fails, {@link SessionOutcome} decides whether there is another and {@link
 * ReconnectSchedule} when; this class only carries that out. The next attempt asks, through a {@link Resumption}, for
 * the names and ports the last established session got, with its resume token.
 *
 * <p>Single-threaded: everything but the {@link TunnelHandle} methods and {@link #start()} runs on the client's event
 * loop, and one connection at a time exists, so nothing about one session can race the next. A connection reports its
 * end only once its channel has closed and its streams are reported closed, and the next attempt is scheduled after
 * that.
 *
 * <p>Without a schedule ({@code ReconnectPolicy.never()}), the first session's end stops the client.
 */
final class SessionSupervisor implements TunnelHandle, ControlConnection.Supervision {

    /** Starts one attempt: a connection to the server that asks for {@code resumption}. Called on the event loop. */
    interface Connector {
        ControlConnection connect(Resumption resumption, ControlConnection.Supervision supervision);
    }

    private static final Logger log = LoggerFactory.getLogger(SessionSupervisor.class);

    private static final String CLOSED_BEFORE_SET_UP = "The client was closed before the session was set up.";

    private final ClientSettings settings;
    private final EventLoop loop;
    private final Connector connector;
    /** Null when the client never reconnects. */
    private final ReconnectSchedule schedule;

    private final CompletableFuture<TunnelHandle> started = new CompletableFuture<>();
    private final CompletableFuture<TunnelClosed> closed = new CompletableFuture<>();
    private final AtomicBoolean closeReported = new AtomicBoolean();

    private ControlConnection current;
    private ScheduledFuture<?> nextAttempt;
    private Resumption resumption = Resumption.none();
    /** The tunnels of the last session established; null until the first is. */
    private List<RegisteredTunnel> previous;

    private boolean stopping;

    private volatile String sessionId;
    private volatile List<RegisteredTunnel> tunnels = List.of();

    SessionSupervisor(
            ClientSettings settings, EventLoop loop, Connector connector, Optional<ReconnectSchedule> schedule) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.loop = Objects.requireNonNull(loop, "loop");
        this.connector = Objects.requireNonNull(connector, "connector");
        this.schedule = Objects.requireNonNull(schedule, "schedule").orElse(null);
    }

    /**
     * Starts the first session and blocks until it is established, or until the client gives up on it.
     *
     * @throws TunnelStartException why the first session couldn't be established
     */
    TunnelHandle start() throws TunnelStartException {
        try {
            loop.execute(this::attempt);
        } catch (RejectedExecutionException e) {
            throw new TunnelStartException(Kind.CONNECT_FAILED, CLOSED_BEFORE_SET_UP);
        }
        try {
            return started.get();
        } catch (ExecutionException e) {
            if (e.getCause() instanceof TunnelStartException failure) {
                throw failure;
            }
            throw new IllegalStateException("start() failed unexpectedly", e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            close();
            throw new TunnelStartException(Kind.CONNECT_FAILED, "Interrupted while the session was being set up.");
        }
    }

    // The handle, from any thread.

    @Override
    public String sessionId() {
        return sessionId;
    }

    @Override
    public List<RegisteredTunnel> tunnels() {
        return tunnels;
    }

    @Override
    public CompletionStage<TunnelClosed> closed() {
        return closed.minimalCompletionStage();
    }

    @Override
    public void close() {
        try {
            loop.execute(this::stop);
        } catch (RejectedExecutionException e) {
            log.debug("close requested after the client stopped");
        }
    }

    /** Waits up to {@code timeout} for a started client to stop. */
    void awaitClosed(Duration timeout) {
        if (!started.isDone() || started.isCompletedExceptionally()) {
            return;
        }
        try {
            closed.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException | TimeoutException e) {
            log.debug("the client didn't finish stopping within {}", timeout);
        }
    }

    /**
     * Completes whatever is still pending once the event loop has stopped, so {@code start()} returns and
     * {@link #closed()} completes even if the loop went away first.
     */
    void abandon() {
        started.completeExceptionally(new TunnelStartException(Kind.CONNECT_FAILED, CLOSED_BEFORE_SET_UP));
        if (!started.isCompletedExceptionally()) {
            reportClosed(TunnelClosed.userClosed());
        }
        closed.complete(TunnelClosed.userClosed());
    }

    /** Whether the current connection has stopped reading, which flow control never does. On the event loop. */
    boolean isReadPaused() {
        return current != null && current.isReadPaused();
    }

    // The sessions, on the event loop.

    @Override
    public void onEstablished(ControlConnection connection, List<RegisteredTunnel> registered) {
        if (schedule != null) {
            schedule.established();
        }
        String id = connection.session().sessionId().orElseThrow().toString();
        boolean first = previous == null;
        boolean sameUrls = !first && urls(previous).equals(urls(registered));
        previous = registered;
        resumption = Resumption.after(
                settings.tunnels(), registered, connection.session().resumeToken());
        sessionId = id;
        tunnels = registered;
        if (first) {
            tell(events -> events.onEstablished(id, registered));
            started.complete(this);
        } else {
            log.debug("reconnected: session={} sameUrls={}", id, sameUrls);
            tell(events -> events.onReconnected(id, registered, sameUrls));
        }
    }

    @Override
    public void onStartFailed(ControlConnection connection, TunnelStartException failure) {
        current = null;
        if (stopping) {
            finish(TunnelClosed.userClosed(), failure);
            return;
        }
        TunnelClosed reason = asClosed(failure);
        if (schedule == null) {
            finish(reason, failure);
            return;
        }
        follow(SessionEnd.ofAttempt(reason, failure.status(), failure.retryAfter(), connection.refusedRef()), failure);
    }

    @Override
    public void onEnded(ControlConnection connection, TunnelClosed reason, Optional<GoAway> goAway) {
        current = null;
        if (schedule != null) {
            schedule.ended();
        }
        if (stopping) {
            finish(TunnelClosed.userClosed(), null);
            return;
        }
        if (schedule == null) {
            finish(reason, null);
            return;
        }
        follow(SessionEnd.ofSession(reason, goAway.map(g -> Duration.ofMillis(g.reconnectAfterMs()))), null);
    }

    private void attempt() {
        nextAttempt = null;
        if (stopping) {
            finish(TunnelClosed.userClosed(), new TunnelStartException(Kind.CONNECT_FAILED, CLOSED_BEFORE_SET_UP));
            return;
        }
        current = connector.connect(resumption, this);
    }

    /** Carries out what {@link SessionOutcome} decides about {@code end}. */
    private void follow(SessionEnd end, TunnelStartException failure) {
        SessionOutcome outcome = SessionOutcome.of(end, previous == null, settings.tunnels(), resumption);
        switch (outcome) {
            case SessionOutcome.Stop stop -> finish(stop.reason(), failure);
            case SessionOutcome.RetryWithoutPinned retry -> {
                log.debug("the name or port tunnel {} had is taken; asking for a new one", retry.ref());
                resumption = resumption.withoutPin(retry.ref());
                retryIn(end.closed(), Duration.ZERO);
            }
            case SessionOutcome.Reconnect reconnect -> {
                Optional<Duration> delay = delay(reconnect.after());
                if (delay.isPresent()) {
                    retryIn(end.closed(), delay.get());
                } else {
                    finish(end.closed(), failure);
                }
            }
        }
    }

    private Optional<Duration> delay(Wait wait) {
        switch (wait) {
            case Wait.AfterLoss loss -> schedule.afterLoss();
            case Wait.AfterGoAway goAway -> schedule.afterGoAway(goAway.reconnectAfter());
            case Wait.AfterRetryAfter refused -> schedule.afterRetryAfter(refused.retryAfter());
            case Wait.StartingUp startingUp -> schedule.startingUp(startingUp.atLeast());
            case Wait.Continue carryOn -> {}
        }
        return schedule.nextDelay();
    }

    private void retryIn(TunnelClosed reason, Duration delay) {
        log.debug(
                "next attempt in {} ms after {} {}",
                delay.toMillis(),
                reason.reason(),
                reason.code().orElse(null));
        if (previous != null) {
            tell(events -> events.onDisconnected(reason, delay));
        }
        nextAttempt = loop.schedule(this::attempt, delay.toNanos(), TimeUnit.NANOSECONDS);
    }

    /** The user stops the client: no further attempt, and the current session closes. */
    private void stop() {
        if (stopping) {
            return;
        }
        stopping = true;
        if (nextAttempt != null) {
            nextAttempt.cancel(false);
            nextAttempt = null;
            finish(TunnelClosed.userClosed(), new TunnelStartException(Kind.CONNECT_FAILED, CLOSED_BEFORE_SET_UP));
        } else if (current != null) {
            current.closeByUser();
        } else {
            finish(TunnelClosed.userClosed(), new TunnelStartException(Kind.CONNECT_FAILED, CLOSED_BEFORE_SET_UP));
        }
    }

    /**
     * The client stops for good. A client whose first session was never established fails {@code start()} with
     * {@code neverEstablished} and reports nothing; one that was reports {@code reason} last.
     */
    private void finish(TunnelClosed reason, TunnelStartException neverEstablished) {
        stopping = true;
        if (previous == null) {
            started.completeExceptionally(Objects.requireNonNull(neverEstablished, "neverEstablished"));
            closed.complete(reason);
            return;
        }
        reportClosed(reason);
    }

    private void reportClosed(TunnelClosed reason) {
        if (closeReported.compareAndSet(false, true)) {
            tell(events -> events.onClosed(reason));
            closed.complete(reason);
        }
    }

    /** How a failed attempt reads as the end of a session, with the status of a refused upgrade. */
    private static TunnelClosed asClosed(TunnelStartException failure) {
        return reasonOf(failure).withUpgradeStatus(failure.status());
    }

    private static TunnelClosed reasonOf(TunnelStartException failure) {
        String message = failure.getMessage();
        return switch (failure.kind()) {
            case SERVER_ERROR, UPGRADE_REFUSED ->
                failure.code()
                        .map(code -> TunnelClosed.serverError(code, message))
                        .orElseGet(() -> TunnelClosed.connectionLost(message));
            case LOCAL_ERROR, TIMEOUT ->
                failure.code()
                        .map(code -> TunnelClosed.localError(code, message))
                        .orElseGet(() -> TunnelClosed.connectionLost(message));
            case CONNECT_FAILED, TLS_FAILED -> TunnelClosed.connectionLost(message);
            case UNSUPPORTED_BY_SERVER -> new TunnelClosed(TunnelClosed.Reason.LOCAL_ERROR, Optional.empty(), message);
        };
    }

    private static List<String> urls(List<RegisteredTunnel> registered) {
        return registered.stream().map(RegisteredTunnel::url).toList();
    }

    private void tell(Consumer<TunnelEvents> event) {
        try {
            event.accept(settings.events());
        } catch (RuntimeException e) {
            log.warn("A TunnelEvents listener failed", e);
        }
    }
}
