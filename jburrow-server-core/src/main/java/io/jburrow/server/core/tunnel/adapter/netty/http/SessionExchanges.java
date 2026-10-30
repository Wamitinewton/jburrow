package io.jburrow.server.core.tunnel.adapter.netty.http;

import io.jburrow.protocol.adapter.netty.Frame;
import io.jburrow.protocol.control.TunnelKind;
import io.jburrow.protocol.flow.Credit;
import io.jburrow.protocol.flow.DataOutcome;
import io.jburrow.protocol.stream.StreamEvent;
import io.jburrow.protocol.stream.StreamOutcome;
import io.jburrow.protocol.wire.ErrorCode;
import io.jburrow.protocol.wire.ProtocolException;
import io.jburrow.protocol.wire.StreamIds;
import io.jburrow.server.core.tunnel.adapter.netty.control.SessionContext;
import io.jburrow.server.core.tunnel.adapter.netty.control.SessionRegistry;
import io.jburrow.server.core.tunnel.application.ParkingBudget;
import io.jburrow.server.core.tunnel.application.StreamQuota;
import io.jburrow.server.core.tunnel.application.port.TunnelMetrics;
import io.jburrow.server.core.tunnel.application.port.TunnelMetrics.Direction;
import io.jburrow.server.core.tunnel.application.port.TunnelMetrics.Side;
import io.jburrow.server.core.tunnel.domain.Session;
import io.jburrow.server.core.tunnel.domain.SessionState;
import io.jburrow.server.core.tunnel.domain.StreamAdmission;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.EventLoop;
import io.netty.util.AttributeKey;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The HTTP exchanges of one session, on the session's event loop: admits each exchange the edge hands over, turns the
 * client's {@code HEADERS}, {@code DATA} and {@code CLOSE} on their streams into messages to the edge, and returns
 * credit as the edge's writes complete ({@code docs/phase-3-http-tunnels/ARCHITECTURE.md} §Concurrency model, §Client to
 * visitor: credit on delivery across loops). The {@link Session} decides what every frame means; this class does the
 * I/O.
 *
 * <p>An exchange is in {@link #exchanges} exactly while the session's stream table holds its stream, so the session's
 * frames reach it here, and a stream that has closed is the session's to discard.
 *
 * <p>Found from the edge through the session channel's attribute, which {@link #attach} sets before the session's
 * tunnels can be routed to.
 *
 * <p>Confined to the session's event loop, apart from {@link #of} and {@link #loop}.
 */
public final class SessionExchanges {

    private static final AttributeKey<SessionExchanges> EXCHANGES =
            AttributeKey.valueOf(SessionExchanges.class, "exchanges");

    private static final Logger log = LoggerFactory.getLogger(SessionExchanges.class);

    /** What a session's exchanges are given, shared by every session. */
    public record Settings(
            Duration responseTimeout,
            long maxRequestBody,
            Duration parkAfter,
            ParkingBudget parking,
            StreamQuota streamQuota,
            TunnelMetrics metrics) {

        public Settings {
            Objects.requireNonNull(responseTimeout, "responseTimeout");
            Objects.requireNonNull(parkAfter, "parkAfter");
            Objects.requireNonNull(parking, "parking");
            Objects.requireNonNull(streamQuota, "streamQuota");
            Objects.requireNonNull(metrics, "metrics");
        }
    }

    private final SessionContext context;
    private final Session session;
    private final Channel sessionChannel;
    private final EventLoop loop;
    private final StreamQuota streamQuota;
    private final TunnelMetrics metrics;
    private final long responseTimeoutNanos;
    private final long maxRequestBody;
    private final LongSupplier nanoTime;
    private final ExchangeGate gate;
    private final Parking parking;
    private final Map<Integer, SessionExchange> exchanges = new LinkedHashMap<>();
    private final List<SessionExchange> written = new ArrayList<>();

    SessionExchanges(SessionContext context, Settings settings, LongSupplier nanoTime) {
        this.context = Objects.requireNonNull(context, "context");
        this.session = context.session();
        this.sessionChannel = context.channel();
        this.loop = sessionChannel.eventLoop();
        this.streamQuota = settings.streamQuota();
        this.metrics = settings.metrics();
        this.responseTimeoutNanos = settings.responseTimeout().toNanos();
        this.maxRequestBody = settings.maxRequestBody();
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
        this.gate = new ExchangeGate(session, sessionChannel, metrics);
        this.parking = new Parking(
                session, settings.parking(), settings.parkAfter(), nanoTime, loop, this::sendCredit, metrics);
    }

    /** Gives the session its exchanges, findable from the edge from now on. Called on the session's event loop. */
    public static SessionExchanges attach(SessionContext context, Settings settings) {
        SessionExchanges exchanges = new SessionExchanges(context, settings, System::nanoTime);
        if (context.channel().attr(EXCHANGES).setIfAbsent(exchanges) != null) {
            throw new IllegalStateException("Session " + context.session().id() + " already has its exchanges");
        }
        return exchanges;
    }

    /** The exchanges of the session on {@code context}'s channel. Safe from any thread. */
    public static Optional<SessionExchanges> of(SessionContext context) {
        return Optional.ofNullable(context.channel().attr(EXCHANGES).get());
    }

    /** Finds a tunnel's session in {@code registry}, for the edge. */
    public static ExchangeSessions in(SessionRegistry registry) {
        Objects.requireNonNull(registry, "registry");
        return tunnel -> registry.find(tunnel).flatMap(SessionExchanges::of);
    }

    /** The session's event loop, where every other method runs. Safe from any thread. */
    EventLoop loop() {
        return loop;
    }

    // An exchange arriving from the edge.

    /**
     * Admits an exchange with phase 2's checks, then opens its stream and sends {@code OPEN}. Refused, the edge answers
     * with a page: {@code TUNNEL_BUSY} at a stream cap, and for a session that has ended, the page for why it ended.
     */
    void open(SessionExchange exchange, ByteBuf leftover, boolean requestEnded) {
        requireLoop();
        Optional<StreamAdmission.Refusal> refusal = session.checkAdmission(exchange.tunnel());
        if (refusal.isPresent()) {
            refuse(exchange, refusal.get(), leftover);
            return;
        }
        if (!streamQuota.tryAcquire()) {
            refuse(exchange, StreamAdmission.Refusal.SERVER_STREAM_LIMIT, leftover);
            return;
        }
        int streamId;
        try {
            streamId = session.openExchange(exchange.tunnel(), exchange.open().request());
        } catch (ProtocolException exhausted) {
            streamQuota.release();
            exchange.refused(EdgeError.TUNNEL_BUSY, leftover);
            context.closer().fail(context, exhausted);
            return;
        }
        exchanges.put(streamId, exchange);
        sessionChannel.writeAndFlush(new Frame.Open(streamId, exchange.open()), sessionChannel.voidPromise());
        metrics.streamOpened(TunnelKind.HTTP);
        log.debug(
                "exchange opened: session={} stream={} host={} method={}",
                session.id(),
                streamId,
                exchange.host(),
                exchange.open().request().method());
        exchange.opened(streamId, nanoTime.getAsLong(), leftover, requestEnded);
    }

    private void refuse(SessionExchange exchange, StreamAdmission.Refusal refusal, ByteBuf leftover) {
        EdgeError error;
        if (refusal != StreamAdmission.Refusal.NOT_ESTABLISHED) {
            metrics.visitorRefused(refusal);
            error = EdgeError.TUNNEL_BUSY;
        } else {
            error = endedSession();
        }
        exchange.refused(error, leftover);
    }

    /** The page for a request to a session that has ended, or is ending, with the reason it is. */
    private EdgeError endedSession() {
        return session.closeReason()
                        .filter(ErrorCode.SERVER_SHUTTING_DOWN::equals)
                        .isPresent()
                ? EdgeError.SERVER_SHUTTING_DOWN
                : EdgeError.TUNNEL_NOT_FOUND;
    }

    // The client's frames.

    /** Whether {@code streamId} carries one of this session's exchanges. */
    public boolean owns(int streamId) {
        return exchanges.containsKey(streamId);
    }

    /**
     * A response head from the client.
     *
     * @throws ProtocolException a session error, if the head breaks its stream's rules
     */
    public void onHeaders(Frame.Headers headers) {
        requireLoop();
        int streamId = headers.streamId();
        switch (session.onHeadersReceived(streamId, headers.head())) {
            case StreamOutcome.Accept accepted -> require(streamId).onResponseHead(headers.head());
            case StreamOutcome.ResetStream reset -> require(streamId).onStreamError(reset.code());
            case StreamOutcome.Discard discarded -> {}
        }
    }

    /**
     * Response body from the client. A delivered payload is held against the windows until its write to the visitor
     * completes or fails. Takes over the frame's buffer, on every path.
     */
    public void onData(Frame.Data data) {
        requireLoop();
        int streamId = data.streamId();
        DataOutcome outcome;
        try {
            outcome = session.onDataReceived(streamId, data.payload().readableBytes());
        } catch (RuntimeException e) {
            data.release();
            throw e;
        }
        switch (outcome) {
            case DataOutcome.Deliver deliver -> require(streamId).onResponseData(data.payload());
            case DataOutcome.Reset reset -> {
                data.release();
                sendCredit(streamId, reset.credit());
                require(streamId).onStreamError(reset.code());
            }
            case DataOutcome.Discard discarded -> {
                data.release();
                sendCredit(streamId, discarded.credit());
            }
        }
    }

    public void onClose(Frame.Close close) {
        requireLoop();
        int streamId = close.streamId();
        boolean reset = close.reset().isPresent();
        StreamOutcome outcome =
                session.onStreamFrame(streamId, reset ? StreamEvent.RST_RECEIVED : StreamEvent.CLOSE_RECEIVED);
        switch (outcome) {
            case StreamOutcome.Accept accepted -> {
                if (reset) {
                    require(streamId).onClientReset(close.reset().orElseThrow());
                } else {
                    require(streamId).onResponseEnd();
                }
            }
            case StreamOutcome.ResetStream streamError -> require(streamId).onStreamError(streamError.code());
            case StreamOutcome.Discard discarded -> {}
        }
    }

    /** A {@code WINDOW_UPDATE} on one of this session's exchange streams: its request body may go on. */
    public void onWindowUpdate(Frame.WindowUpdate update) {
        requireLoop();
        switch (session.onWindowUpdate(update.streamId(), update.increment())) {
            case STREAM -> gate.onStreamCredit(require(update.streamId()));
            case SESSION, IGNORED, DISCARDED -> {}
        }
    }

    /** Session credit arrived, already counted by the session: exchanges waiting for it go on. */
    public void onSessionCredit() {
        requireLoop();
        gate.onSessionCredit();
    }

    public void onSessionWritabilityChanged(boolean writable) {
        requireLoop();
        if (session.state() != SessionState.CLOSING) {
            gate.onSessionWritable(writable);
        }
    }

    /** The session's connection finished a read batch: flush what it wrote towards visitors. */
    public void onReadComplete() {
        requireLoop();
        if (written.isEmpty()) {
            return;
        }
        for (SessionExchange exchange : List.copyOf(written)) {
            exchange.flushEdge();
        }
        written.clear();
    }

    /**
     * Ends every exchange because the session is ending: each stream reset, and each visitor answered with the page for
     * why, or cut off if its response had started. Safe to call more than once.
     */
    public void cancelAll() {
        requireLoop();
        parking.stop();
        EdgeError error = endedSession();
        for (SessionExchange exchange : List.copyOf(exchanges.values())) {
            exchange.cancel(error);
        }
    }

    /** The flow state of each exchange, for diagnosing a stall. Never a byte of a body, nor a request's target. */
    public String describeFlow() {
        StringBuilder description = new StringBuilder()
                .append("exchanges waiting for the session=")
                .append(gate.line().stream().map(ExchangeGate.Reader::streamId).toList())
                .append(", parked=")
                .append(parking.parked());
        for (SessionExchange exchange : exchanges.values()) {
            description
                    .append("\n  exchange ")
                    .append(exchange.streamId())
                    .append(": ")
                    .append(session.describeStream(exchange.streamId()))
                    .append(", ")
                    .append(exchange.describe());
        }
        return description.toString();
    }

    // What an exchange asks of its session.

    /**
     * A response write of {@code bytes} on {@code streamId} completed or failed: its bytes are no longer held, so their
     * credit goes back, and whatever of them was parked goes back to the budget. Runs whether or not the exchange has
     * ended, since every byte is released exactly once.
     */
    void onWritten(int streamId, int bytes) {
        requireLoop();
        sendCredit(streamId, parking.onReleased(streamId, bytes));
    }

    void sendData(int streamId, ByteBuf data) {
        sessionChannel.write(new Frame.Data(streamId, data), sessionChannel.voidPromise());
    }

    void sendClose(int streamId) {
        session.onStreamSent(streamId, StreamEvent.CLOSE_SENT);
        sessionChannel.writeAndFlush(Frame.Close.half(streamId), sessionChannel.voidPromise());
    }

    /** Resets an open stream with {@code CANCELLED} from this side. */
    void resetStream(int streamId) {
        session.onStreamSent(streamId, StreamEvent.RST_SENT);
        sendReset(streamId, ErrorCode.CANCELLED);
    }

    /** Sends {@code CLOSE RST} for a stream the session has already closed. */
    void sendReset(int streamId, ErrorCode code) {
        if (sessionChannel.isActive()) {
            sessionChannel.writeAndFlush(Frame.Close.reset(streamId, code), sessionChannel.voidPromise());
        }
        streamReset(code, true);
    }

    void streamReset(ErrorCode code, boolean local) {
        metrics.streamReset(code, local ? Side.LOCAL : Side.REMOTE);
    }

    void flushAtReadComplete(SessionExchange exchange) {
        written.add(exchange);
    }

    /** Forgets an exchange whose stream the session's stream table has closed. */
    void finished(SessionExchange exchange, long bytesIn, long bytesOut, String ending) {
        int streamId = exchange.streamId();
        exchanges.remove(streamId);
        streamQuota.release();
        written.remove(exchange);
        gate.onRemoved(exchange);
        parking.onEnded(streamId);
        context.streamServed(bytesIn, bytesOut);
        if (bytesIn > 0) {
            metrics.tunnelBytes(TunnelKind.HTTP, Direction.INBOUND, bytesIn);
        }
        if (bytesOut > 0) {
            metrics.tunnelBytes(TunnelKind.HTTP, Direction.OUTBOUND, bytesOut);
        }
        metrics.streamClosed(TunnelKind.HTTP);
        log.debug(
                "exchange closed: session={} stream={} bytesIn={} bytesOut={} ending=\"{}\"",
                session.id(),
                streamId,
                bytesIn,
                bytesOut,
                ending);
    }

    /**
     * Sends the {@code WINDOW_UPDATE}s {@code credit} names, each flushed at once. Nothing goes to a session that is
     * closing, whose credit no longer matters.
     */
    private void sendCredit(int streamId, Credit credit) {
        if (credit.isEmpty() || !sessionChannel.isActive() || session.state() == SessionState.CLOSING) {
            return;
        }
        if (credit.session() > 0) {
            sessionChannel.writeAndFlush(
                    new Frame.WindowUpdate(StreamIds.CONTROL, credit.session()), sessionChannel.voidPromise());
        }
        if (credit.stream() > 0) {
            sessionChannel.writeAndFlush(
                    new Frame.WindowUpdate(streamId, credit.stream()), sessionChannel.voidPromise());
        }
    }

    Session session() {
        return session;
    }

    Channel sessionChannel() {
        return sessionChannel;
    }

    ExchangeGate gate() {
        return gate;
    }

    Parking parking() {
        return parking;
    }

    TunnelMetrics metrics() {
        return metrics;
    }

    long nanoTime() {
        return nanoTime.getAsLong();
    }

    long responseTimeoutNanos() {
        return responseTimeoutNanos;
    }

    long maxRequestBody() {
        return maxRequestBody;
    }

    private SessionExchange require(int streamId) {
        SessionExchange exchange = exchanges.get(streamId);
        if (exchange == null) {
            throw new IllegalStateException(
                    "Stream " + streamId + " of session " + session.id() + " is open but has no exchange");
        }
        return exchange;
    }

    private void requireLoop() {
        if (!loop.inEventLoop()) {
            throw new IllegalStateException("Session " + session.id() + "'s exchanges touched off its event loop");
        }
    }
}
