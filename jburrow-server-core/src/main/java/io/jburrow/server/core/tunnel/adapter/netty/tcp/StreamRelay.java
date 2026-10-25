package io.jburrow.server.core.tunnel.adapter.netty.tcp;

import io.jburrow.protocol.adapter.netty.Frame;
import io.jburrow.protocol.control.Open;
import io.jburrow.protocol.control.RemoteAddress;
import io.jburrow.protocol.stream.StreamEvent;
import io.jburrow.protocol.stream.StreamOutcome;
import io.jburrow.protocol.stream.StreamState;
import io.jburrow.protocol.wire.ErrorCode;
import io.jburrow.protocol.wire.ProtocolException;
import io.jburrow.server.core.tunnel.adapter.netty.control.SessionContext;
import io.jburrow.server.core.tunnel.adapter.netty.control.SessionStreams;
import io.jburrow.server.core.tunnel.application.port.TunnelMetrics;
import io.jburrow.server.core.tunnel.application.port.TunnelMetrics.Direction;
import io.jburrow.server.core.tunnel.application.port.TunnelMetrics.Side;
import io.jburrow.server.core.tunnel.domain.Session;
import io.jburrow.server.core.tunnel.domain.SessionState;
import io.jburrow.server.core.tunnel.domain.StreamAdmission;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPromise;
import io.netty.channel.socket.DuplexChannel;
import io.netty.util.concurrent.ScheduledFuture;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Relays the bytes of one session's streams, both ways, without copying them, and maps each stream to the TCP
 * connection it carries ({@code docs/protocol.md} §9.5): a visitor's {@code FIN} becomes {@code CLOSE}, the client's
 * {@code CLOSE} shuts down the visitor's write side, and resets travel as {@code CLOSE RST} one way and TCP {@code RST}
 * the other. The session's {@link Session} decides what each event means; this class does the I/O.
 *
 * <p>A stream is in {@link #streams} exactly while the session's stream table holds it. When the table closes it, the
 * stream is finished here at once: counted, logged and forgotten, before its connection is closed, so the connection's
 * own closing finds nothing left to reset.
 *
 * <p>Writes are flushed once per read batch, never per frame: towards the client when a visitor's read batch
 * completes, towards visitors when the session's does.
 *
 * <p>Confined to the session's event loop.
 */
final class StreamRelay implements SessionStreams {

    /** How often a long-lived stream's byte counts reach the metrics, which otherwise move only when it ends. */
    static final Duration BYTE_REPORT_INTERVAL = Duration.ofSeconds(10);

    private static final Logger log = LoggerFactory.getLogger(StreamRelay.class);

    private final SessionContext context;
    private final Session session;
    private final Channel sessionChannel;
    private final TunnelMetrics metrics;
    private final Backpressure backpressure;
    private final LongSupplier nanoTime;
    private final long byteReportNanos;
    private final Map<Integer, VisitorHandler> streams = new LinkedHashMap<>();
    private final List<VisitorHandler> written = new ArrayList<>();

    private ScheduledFuture<?> byteReport;

    StreamRelay(SessionContext context, TunnelMetrics metrics) {
        this(context, metrics, System::nanoTime, BYTE_REPORT_INTERVAL);
    }

    StreamRelay(SessionContext context, TunnelMetrics metrics, LongSupplier nanoTime, Duration byteReportInterval) {
        this.context = Objects.requireNonNull(context, "context");
        this.session = context.session();
        this.sessionChannel = context.channel();
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.backpressure = new Backpressure(context, metrics);
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
        this.byteReportNanos = byteReportInterval.toNanos();
    }

    // Visitor to client.

    /**
     * A visitor connected. Admitted: send {@code OPEN}, then start reading the visitor, never before. Refused: close
     * the connection at once; the client is not told.
     */
    void onVisitorConnected(VisitorHandler visitor) {
        StreamAdmission admission;
        try {
            admission = session.openStream(visitor.tunnel().id());
        } catch (ProtocolException exhausted) {
            visitor.channel().close();
            context.closer().fail(context, exhausted);
            return;
        }
        switch (admission) {
            case StreamAdmission.Refused refused -> {
                metrics.visitorRefused(refused.reason());
                log.debug(
                        "visitor refused: session={} reason={} visitor={}",
                        session.id(),
                        refused.reason().label(),
                        describe(visitor.remoteAddress()));
                visitor.channel().close();
            }
            case StreamAdmission.Admitted admitted -> open(visitor, admitted.streamId());
        }
    }

    private void open(VisitorHandler visitor, int streamId) {
        visitor.opened(streamId, nanoTime.getAsLong());
        streams.put(streamId, visitor);
        InetSocketAddress remote = visitor.remoteAddress();
        Open open = new Open(
                visitor.tunnel().id().value(),
                visitor.tunnel().kind(),
                new RemoteAddress(ipLiteral(remote), remote.getPort()));
        sessionChannel.writeAndFlush(new Frame.Open(streamId, open), sessionChannel.voidPromise());
        metrics.streamOpened(visitor.tunnel().kind());
        backpressure.onOpened(visitor);
        scheduleByteReport();
        log.debug("stream opened: session={} stream={} visitor={}", session.id(), streamId, describe(remote));
    }

    /** Takes over {@code bytes}. */
    void onVisitorData(VisitorHandler visitor, ByteBuf bytes) {
        if (!isOpen(visitor) || session.state() == SessionState.CLOSING || !bytes.isReadable()) {
            bytes.release();
            return;
        }
        int streamId = visitor.streamId();
        session.onStreamSent(streamId, StreamEvent.DATA_SENT);
        visitor.countIn(bytes.readableBytes());
        sessionChannel.write(new Frame.Data(streamId, bytes), sessionChannel.voidPromise());
    }

    void onVisitorReadComplete() {
        sessionChannel.flush();
    }

    /** The visitor sent {@code FIN}: half-close the stream, and finish it if the client had already closed its half. */
    void onVisitorInputShutdown(VisitorHandler visitor) {
        if (!isOpen(visitor) || session.state() == SessionState.CLOSING) {
            return;
        }
        int streamId = visitor.streamId();
        session.onStreamSent(streamId, StreamEvent.CLOSE_SENT);
        sessionChannel.writeAndFlush(Frame.Close.half(streamId), sessionChannel.voidPromise());
        if (session.streamState(streamId).isEmpty()) {
            finish(visitor, "closed");
            closeAfterWrites(visitor.channel());
        }
    }

    void onVisitorWritabilityChanged(VisitorHandler visitor, boolean writable) {
        if (isOpen(visitor)) {
            backpressure.onVisitorWritabilityChanged(visitor, writable);
        }
    }

    /** The visitor's connection failed, or a write to it did: an I/O error is the visitor resetting. */
    void onVisitorFailed(VisitorHandler visitor, Throwable cause) {
        if (!isOpen(visitor)) {
            return;
        }
        if (cause instanceof IOException) {
            log.debug(
                    "visitor connection failed: session={} stream={}: {}",
                    session.id(),
                    visitor.streamId(),
                    cause.toString());
            resetFromVisitor(visitor, ErrorCode.PEER_RESET);
        } else {
            log.error("Internal error relaying stream {} of session {}", visitor.streamId(), session.id(), cause);
            resetFromVisitor(visitor, ErrorCode.INTERNAL_ERROR);
        }
    }

    /** The visitor's connection closed. A stream still open then was cut, not closed. */
    void onVisitorInactive(VisitorHandler visitor) {
        if (isOpen(visitor)) {
            resetFromVisitor(visitor, ErrorCode.PEER_RESET);
        }
    }

    private void resetFromVisitor(VisitorHandler visitor, ErrorCode code) {
        int streamId = visitor.streamId();
        session.onStreamSent(streamId, StreamEvent.RST_SENT);
        if (sessionChannel.isActive()) {
            sessionChannel.writeAndFlush(Frame.Close.reset(streamId, code), sessionChannel.voidPromise());
        }
        metrics.streamReset(code, Side.LOCAL);
        finish(visitor, "reset " + code.code());
        visitor.channel().close();
    }

    // Client to visitor.

    @Override
    public void onData(Frame.Data data) {
        StreamOutcome outcome;
        try {
            outcome = session.onStreamFrame(data.streamId(), StreamEvent.DATA_RECEIVED);
        } catch (RuntimeException e) {
            data.release();
            throw e;
        }
        switch (outcome) {
            case StreamOutcome.Accept accepted -> {
                VisitorHandler visitor = require(data.streamId());
                ByteBuf payload = data.payload();
                visitor.countOut(payload.readableBytes());
                visitor.channel().write(payload, visitor.channel().voidPromise());
                if (visitor.markFlushPending()) {
                    written.add(visitor);
                }
            }
            case StreamOutcome.ResetStream reset -> {
                data.release();
                resetFromLocal(require(data.streamId()), reset.code());
            }
            case StreamOutcome.Discard discarded -> data.release();
        }
    }

    @Override
    public void onClose(Frame.Close close) {
        boolean reset = close.reset().isPresent();
        StreamOutcome outcome =
                session.onStreamFrame(close.streamId(), reset ? StreamEvent.RST_RECEIVED : StreamEvent.CLOSE_RECEIVED);
        switch (outcome) {
            case StreamOutcome.Accept accepted -> {
                VisitorHandler visitor = require(close.streamId());
                if (reset) {
                    ErrorCode code = close.reset().orElseThrow();
                    metrics.streamReset(code, Side.REMOTE);
                    finish(visitor, "reset by client " + code.code());
                    resetConnection(visitor.channel());
                } else if (accepted.next() == StreamState.HALF_CLOSED_REMOTE) {
                    shutdownOutputAfterWrites(visitor.channel());
                } else {
                    finish(visitor, "closed");
                    shutdownOutputAfterWrites(visitor.channel()).addListener(ChannelFutureListener.CLOSE);
                }
            }
            case StreamOutcome.ResetStream streamError -> resetFromLocal(require(close.streamId()), streamError.code());
            case StreamOutcome.Discard discarded -> {}
        }
    }

    @Override
    public void onReadComplete() {
        if (written.isEmpty()) {
            return;
        }
        for (VisitorHandler visitor : written) {
            visitor.flush();
        }
        written.clear();
    }

    @Override
    public void onSessionWritabilityChanged(boolean writable) {
        if (session.state() != SessionState.CLOSING) {
            backpressure.onSessionWritabilityChanged(writable, streams.values());
        }
    }

    /**
     * The client broke a stream rule: the session's stream table has already closed the stream, so this sends the reset
     * without recording one.
     */
    private void resetFromLocal(VisitorHandler visitor, ErrorCode code) {
        sessionChannel.writeAndFlush(Frame.Close.reset(visitor.streamId(), code), sessionChannel.voidPromise());
        metrics.streamReset(code, Side.LOCAL);
        finish(visitor, "reset " + code.code());
        resetConnection(visitor.channel());
    }

    // The session ending.

    @Override
    public void cancelAll() {
        cancelByteReport();
        if (streams.isEmpty()) {
            return;
        }
        boolean tellClient = sessionChannel.isActive();
        for (VisitorHandler visitor : List.copyOf(streams.values())) {
            int streamId = visitor.streamId();
            session.onStreamSent(streamId, StreamEvent.RST_SENT);
            if (tellClient) {
                sessionChannel.write(Frame.Close.reset(streamId, ErrorCode.CANCELLED), sessionChannel.voidPromise());
            }
            metrics.streamReset(ErrorCode.CANCELLED, Side.LOCAL);
            finish(visitor, "cancelled");
            resetConnection(visitor.channel());
        }
    }

    /** The connections of the open streams. */
    List<Channel> visitorChannels() {
        return streams.values().stream().<Channel>map(VisitorHandler::channel).toList();
    }

    // Bookkeeping.

    private boolean isOpen(VisitorHandler visitor) {
        return visitor.hasStream() && streams.get(visitor.streamId()) == visitor;
    }

    private VisitorHandler require(int streamId) {
        VisitorHandler visitor = streams.get(streamId);
        if (visitor == null) {
            throw new IllegalStateException(
                    "Stream " + streamId + " of session " + session.id() + " is open but has no visitor");
        }
        return visitor;
    }

    /** Forgets a stream the session's stream table has closed: its counters reach the session and the metrics. */
    private void finish(VisitorHandler visitor, String ending) {
        streams.remove(visitor.streamId());
        written.remove(visitor);
        backpressure.onRemoved(visitor);
        reportBytes(visitor);
        context.streamServed(visitor.bytesIn(), visitor.bytesOut());
        metrics.streamClosed(visitor.tunnel().kind());
        if (streams.isEmpty()) {
            cancelByteReport();
        }
        log.info(
                "stream closed: session={} stream={} visitor={} bytesIn={} bytesOut={} duration={}ms ending=\"{}\"",
                session.id(),
                visitor.streamId(),
                describe(visitor.remoteAddress()),
                visitor.bytesIn(),
                visitor.bytesOut(),
                TimeUnit.NANOSECONDS.toMillis(nanoTime.getAsLong() - visitor.openedNanos()),
                ending);
    }

    private void reportBytes(VisitorHandler visitor) {
        long in = visitor.takeUnreportedIn();
        if (in > 0) {
            metrics.tunnelBytes(visitor.tunnel().kind(), Direction.INBOUND, in);
        }
        long out = visitor.takeUnreportedOut();
        if (out > 0) {
            metrics.tunnelBytes(visitor.tunnel().kind(), Direction.OUTBOUND, out);
        }
    }

    private void scheduleByteReport() {
        if (byteReport == null) {
            byteReport = sessionChannel
                    .eventLoop()
                    .scheduleAtFixedRate(
                            this::reportOpenStreams, byteReportNanos, byteReportNanos, TimeUnit.NANOSECONDS);
        }
    }

    private void reportOpenStreams() {
        for (VisitorHandler visitor : streams.values()) {
            reportBytes(visitor);
        }
    }

    private void cancelByteReport() {
        if (byteReport != null) {
            byteReport.cancel(false);
            byteReport = null;
        }
    }

    // Connection handling.

    /**
     * Shuts down the write side once everything written before is on the wire: Netty fails writes still queued when
     * the output shuts down, so shutting down at once would drop the end of the stream.
     */
    private static ChannelFuture shutdownOutputAfterWrites(DuplexChannel channel) {
        ChannelPromise shutdown = channel.newPromise();
        channel.writeAndFlush(Unpooled.EMPTY_BUFFER).addListener(written -> {
            if (channel.isActive() && !channel.isOutputShutdown()) {
                channel.shutdownOutput(shutdown);
            } else {
                shutdown.trySuccess();
            }
        });
        return shutdown;
    }

    private static void closeAfterWrites(Channel channel) {
        channel.writeAndFlush(Unpooled.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE);
    }

    /** Closes with a TCP {@code RST} rather than a {@code FIN}, dropping whatever is still queued. */
    private static void resetConnection(Channel channel) {
        if (channel.isOpen()) {
            channel.config().setOption(ChannelOption.SO_LINGER, 0);
        }
        channel.close();
    }

    /** The address as an IP literal, without an IPv6 scope, which the {@code OPEN} payload doesn't carry. */
    private static String ipLiteral(InetSocketAddress address) {
        String literal = address.getAddress().getHostAddress();
        int scope = literal.indexOf('%');
        return scope < 0 ? literal : literal.substring(0, scope);
    }

    private static String describe(InetSocketAddress address) {
        return address == null ? "-" : ipLiteral(address) + ":" + address.getPort();
    }
}
