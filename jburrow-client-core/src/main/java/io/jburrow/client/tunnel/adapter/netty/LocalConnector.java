package io.jburrow.client.tunnel.adapter.netty;

import io.jburrow.client.tunnel.domain.ActiveTunnel;
import io.jburrow.client.tunnel.domain.ClientSession;
import io.jburrow.client.tunnel.domain.LocalTarget;
import io.jburrow.client.tunnel.domain.PendingData;
import io.jburrow.protocol.adapter.netty.Frame;
import io.jburrow.protocol.control.RemoteAddress;
import io.jburrow.protocol.stream.StreamEvent;
import io.jburrow.protocol.stream.StreamOutcome;
import io.jburrow.protocol.stream.StreamState;
import io.jburrow.protocol.wire.ErrorCode;
import io.jburrow.protocol.wire.FrameLimits;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.AdaptiveRecvByteBufAllocator;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFactory;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPromise;
import io.netty.channel.WriteBufferWaterMark;
import io.netty.channel.socket.DuplexChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.util.concurrent.Future;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The streams of one session on the client: connects each {@code OPEN} to its tunnel's local target, relays bytes both
 * ways, and maps each stream to the TCP connection it carries ({@code docs/protocol.md} §9.5): the local target's
 * {@code FIN} becomes {@code CLOSE}, the server's {@code CLOSE} shuts down the local connection's write side, and resets
 * travel as {@code CLOSE RST} one way and TCP {@code RST} the other. The client's mirror of the server's stream relay,
 * with "visitor" read as "local connection". The {@link ClientSession} decides what each event means; this class does
 * the I/O.
 *
 * <p>Opening is optimistic (§9.4): {@code DATA} that arrives while a local connection is being made is held, and past
 * {@link #MAX_PENDING_BYTES} the control connection is no longer read until the connection is made or fails.
 *
 * <p>Backpressure, ARCHITECTURE.md §Backpressure in phase 1: when the control connection can't take more, no local
 * connection is read; when a local connection can't take more, or holds too much while connecting, the control
 * connection isn't read and its liveness clock is paused. TCP's own flow control carries the pressure on from there.
 *
 * <p>Every local connection is registered on the session's event loop, so all of this is confined to one thread.
 */
final class LocalConnector {

    static final int MAX_PENDING_BYTES = 256 * 1024;
    static final int CONNECT_TIMEOUT_MILLIS = 10_000;
    static final WriteBufferWaterMark LOCAL_WRITE_BUFFER = new WriteBufferWaterMark(32 * 1024, 128 * 1024);

    private static final Logger log = LoggerFactory.getLogger(LocalConnector.class);

    /** Handlers are added to a local connection once it is made, so it starts with none. */
    private static final ChannelInitializer<Channel> NO_HANDLERS = new ChannelInitializer<>() {
        @Override
        protected void initChannel(Channel ch) {}
    };

    private final ControlConnection connection;
    private final ClientSession session;
    private final Channel control;
    private final ClientLivenessHandler liveness;
    private final Dialer dialer;
    private final Bootstrap bootstrap;
    private final LongSupplier nanoTime;
    private final Map<Integer, LocalRelayHandler> streams = new LinkedHashMap<>();
    private final List<LocalRelayHandler> written = new ArrayList<>();
    /** The streams that keep the control connection from being read. */
    private final Set<LocalRelayHandler> pausing = new LinkedHashSet<>();

    LocalConnector(
            ControlConnection connection,
            Channel control,
            ClientLivenessHandler liveness,
            Dialer dialer,
            LongSupplier nanoTime) {
        this.connection = Objects.requireNonNull(connection, "connection");
        this.session = connection.session();
        this.control = Objects.requireNonNull(control, "control");
        this.liveness = Objects.requireNonNull(liveness, "liveness");
        this.dialer = Objects.requireNonNull(dialer, "dialer");
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
        this.bootstrap = new Bootstrap()
                .group(control.eventLoop())
                .channelFactory((ChannelFactory<NioSocketChannel>) NioSocketChannel::new)
                .option(ChannelOption.TCP_NODELAY, true)
                .option(ChannelOption.ALLOW_HALF_CLOSURE, true)
                .option(ChannelOption.AUTO_READ, false)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, CONNECT_TIMEOUT_MILLIS)
                .option(
                        ChannelOption.RECVBUF_ALLOCATOR,
                        new AdaptiveRecvByteBufAllocator(64, 2_048, FrameLimits.MAX_DATA_PAYLOAD))
                .option(ChannelOption.WRITE_BUFFER_WATER_MARK, LOCAL_WRITE_BUFFER)
                .handler(NO_HANDLERS);
    }

    // Server to local target.

    /** The server opened a stream: connect it to its tunnel's local target. */
    void open(Frame.Open open) {
        int streamId = open.streamId();
        ActiveTunnel tunnel = session.onOpen(streamId, open.message());
        LocalRelayHandler stream =
                new LocalRelayHandler(this, streamId, tunnel, nanoTime.getAsLong(), new PendingData(MAX_PENDING_BYTES));
        streams.put(streamId, stream);
        RemoteAddress remote = open.message().remote();
        log.debug(
                "stream opened: stream={} visitor={}:{} local={}",
                streamId,
                remote.address(),
                remote.port(),
                tunnel.target());
        connection.tell(events -> events.onStreamOpened(streamId, tunnel.ref(), remote.address(), remote.port()));
        LocalTarget target = tunnel.target();
        Future<Channel> dialing = dialer.dial(bootstrap, control.eventLoop(), target.host(), target.port());
        stream.dialing(dialing);
        dialing.addListener(done -> onDialed(stream, dialing));
    }

    /** Takes over the frame's buffer. */
    void onData(Frame.Data data) {
        StreamOutcome outcome;
        try {
            outcome = session.onStreamFrame(data.streamId(), StreamEvent.DATA_RECEIVED);
        } catch (RuntimeException e) {
            data.release();
            throw e;
        }
        switch (outcome) {
            case StreamOutcome.Accept accepted -> {
                LocalRelayHandler stream = require(data.streamId());
                ByteBuf payload = data.payload();
                stream.countIn(payload.readableBytes());
                if (stream.isConnected()) {
                    stream.channel().write(payload, stream.channel().voidPromise());
                    if (stream.markFlushPending()) {
                        written.add(stream);
                    }
                } else if (stream.hold(payload)) {
                    reassess(stream);
                }
            }
            case StreamOutcome.ResetStream reset -> {
                data.release();
                resetFromServerFault(require(data.streamId()), reset.code());
            }
            case StreamOutcome.Discard discarded -> data.release();
        }
    }

    void onClose(Frame.Close close) {
        boolean reset = close.reset().isPresent();
        StreamOutcome outcome =
                session.onStreamFrame(close.streamId(), reset ? StreamEvent.RST_RECEIVED : StreamEvent.CLOSE_RECEIVED);
        switch (outcome) {
            case StreamOutcome.Accept accepted -> {
                LocalRelayHandler stream = require(close.streamId());
                if (reset) {
                    finish(
                            stream,
                            close.reset(),
                            "reset by the server " + close.reset().orElseThrow());
                    resetConnection(stream);
                } else if (accepted.next() == StreamState.HALF_CLOSED_REMOTE) {
                    if (stream.isConnected()) {
                        shutdownOutputAfterWrites(stream.channel());
                    } else {
                        stream.shutdownOutputOnConnect();
                    }
                } else {
                    finish(stream, Optional.empty(), "closed");
                    shutdownOutputAfterWrites(stream.channel()).addListener(ChannelFutureListener.CLOSE);
                }
            }
            case StreamOutcome.ResetStream streamError ->
                resetFromServerFault(require(close.streamId()), streamError.code());
            case StreamOutcome.Discard discarded -> {}
        }
    }

    /** The control connection's read batch is done: flush every local connection it wrote to. */
    void onReadComplete() {
        if (written.isEmpty()) {
            return;
        }
        for (LocalRelayHandler stream : written) {
            stream.flush();
        }
        written.clear();
    }

    void onControlWritabilityChanged(boolean writable) {
        if (session.isClosing()) {
            return;
        }
        for (LocalRelayHandler stream : streams.values()) {
            if (stream.isConnected() && (!writable || !stream.channel().isInputShutdown())) {
                stream.channel().config().setAutoRead(writable);
            }
        }
    }

    // Local target to server.

    /** Takes over {@code bytes}. Splits them into {@code DATA} frames no longer than the server allows (§6.5). */
    void onLocalData(LocalRelayHandler stream, ByteBuf bytes) {
        if (!isOpen(stream) || session.isClosing() || !bytes.isReadable()) {
            bytes.release();
            return;
        }
        int streamId = stream.streamId();
        int maxPayload = session.maxFramePayload();
        stream.countOut(bytes.readableBytes());
        try {
            while (bytes.readableBytes() > maxPayload) {
                session.onStreamSent(streamId, StreamEvent.DATA_SENT);
                control.write(new Frame.Data(streamId, bytes.readRetainedSlice(maxPayload)), control.voidPromise());
            }
            session.onStreamSent(streamId, StreamEvent.DATA_SENT);
        } catch (RuntimeException e) {
            bytes.release();
            throw e;
        }
        control.write(new Frame.Data(streamId, bytes), control.voidPromise());
    }

    void onLocalReadComplete() {
        control.flush();
    }

    /** The local target sent {@code FIN}: half-close the stream, and finish it if the server had closed its half. */
    void onLocalInputShutdown(LocalRelayHandler stream) {
        if (!isOpen(stream) || session.isClosing()) {
            return;
        }
        int streamId = stream.streamId();
        session.onStreamSent(streamId, StreamEvent.CLOSE_SENT);
        control.writeAndFlush(Frame.Close.half(streamId), control.voidPromise());
        if (session.streamState(streamId).isEmpty()) {
            finish(stream, Optional.empty(), "closed");
            closeAfterWrites(stream.channel());
        }
    }

    void onLocalWritabilityChanged(LocalRelayHandler stream) {
        if (isOpen(stream)) {
            reassess(stream);
        }
    }

    /** The local connection failed, or a write to it did: an I/O error is the local target resetting. */
    void onLocalFailed(LocalRelayHandler stream, Throwable cause) {
        if (!isOpen(stream)) {
            return;
        }
        if (cause instanceof IOException) {
            log.debug("local connection failed: stream={}: {}", stream.streamId(), cause.toString());
            resetFromLocal(stream, ErrorCode.PEER_RESET);
        } else {
            log.error("Internal error relaying stream {}", stream.streamId(), cause);
            resetFromLocal(stream, ErrorCode.INTERNAL_ERROR);
        }
    }

    /** The local connection closed. A stream still open then was cut, not closed. */
    void onLocalInactive(LocalRelayHandler stream) {
        if (isOpen(stream)) {
            resetFromLocal(stream, ErrorCode.PEER_RESET);
        }
    }

    // The session ending.

    /**
     * Resets every open stream, because the session is ending (§9.6): {@code CLOSE RST CANCELLED} first when
     * {@code tellServer} and the connection can still carry it, and a TCP {@code RST} to each local connection.
     */
    void cancelAll(boolean tellServer) {
        if (streams.isEmpty()) {
            return;
        }
        boolean tell = tellServer && control.isActive();
        for (LocalRelayHandler stream : List.copyOf(streams.values())) {
            session.onStreamSent(stream.streamId(), StreamEvent.RST_SENT);
            if (tell) {
                control.write(Frame.Close.reset(stream.streamId(), ErrorCode.CANCELLED), control.voidPromise());
            }
            finish(stream, Optional.of(ErrorCode.CANCELLED), "cancelled");
            resetConnection(stream);
        }
    }

    // Connecting.

    private void onDialed(LocalRelayHandler stream, Future<Channel> dialed) {
        if (dialed.isCancelled()) {
            return;
        }
        if (!dialed.isSuccess()) {
            if (isOpen(stream)) {
                unreachable(stream, dialed.cause());
            }
            return;
        }
        DuplexChannel channel = (DuplexChannel) dialed.getNow();
        if (!isOpen(stream)) {
            resetConnection(channel);
            return;
        }
        stream.connected(channel);
        channel.pipeline().addLast(stream);
        stream.writePending();
        if (stream.isShutdownOutputOnConnect()) {
            shutdownOutputAfterWrites(channel);
        }
        reassess(stream);
        channel.config().setAutoRead(control.isWritable());
    }

    /** Nothing accepted the connection to the local target: the stream is reset, the session stays up (§9.4). */
    private void unreachable(LocalRelayHandler stream, Throwable cause) {
        log.debug(
                "local target unreachable: stream={} local={}: {}",
                stream.streamId(),
                stream.tunnel().target(),
                cause.toString());
        resetFromLocal(stream, ErrorCode.LOCAL_SERVICE_UNREACHABLE);
    }

    // Resets.

    /** This side resets the stream: record it, tell the server, and cut the local connection. */
    private void resetFromLocal(LocalRelayHandler stream, ErrorCode code) {
        int streamId = stream.streamId();
        session.onStreamSent(streamId, StreamEvent.RST_SENT);
        if (control.isActive()) {
            control.writeAndFlush(Frame.Close.reset(streamId, code), control.voidPromise());
        }
        finish(stream, Optional.of(code), "reset " + code.code());
        resetConnection(stream);
    }

    /**
     * The server broke a stream rule: the session has already closed the stream, so this sends the reset without
     * recording one.
     */
    private void resetFromServerFault(LocalRelayHandler stream, ErrorCode code) {
        control.writeAndFlush(Frame.Close.reset(stream.streamId(), code), control.voidPromise());
        finish(stream, Optional.of(code), "reset " + code.code());
        resetConnection(stream);
    }

    // Bookkeeping.

    private boolean isOpen(LocalRelayHandler stream) {
        return streams.get(stream.streamId()) == stream;
    }

    private LocalRelayHandler require(int streamId) {
        LocalRelayHandler stream = streams.get(streamId);
        if (stream == null) {
            throw new IllegalStateException("Stream " + streamId + " is open but has no local connection");
        }
        return stream;
    }

    /** Forgets a stream the session has closed, and reports it. */
    private void finish(LocalRelayHandler stream, Optional<ErrorCode> reset, String ending) {
        streams.remove(stream.streamId());
        written.remove(stream);
        stream.releasePending();
        if (pausing.remove(stream) && pausing.isEmpty()) {
            resumeControl();
        }
        long durationMillis = TimeUnit.NANOSECONDS.toMillis(nanoTime.getAsLong() - stream.openedNanos());
        log.debug(
                "stream closed: stream={} bytesIn={} bytesOut={} duration={}ms ending=\"{}\"",
                stream.streamId(),
                stream.bytesIn(),
                stream.bytesOut(),
                durationMillis,
                ending);
        connection.tell(events ->
                events.onStreamClosed(stream.streamId(), stream.bytesIn(), stream.bytesOut(), durationMillis, reset));
    }

    /**
     * Whether {@code stream} keeps the control connection from being read: it holds too much while connecting, or its
     * local connection can't take more. The control connection is read again once no stream holds it.
     */
    private void reassess(LocalRelayHandler stream) {
        boolean holds = stream.isPendingOverLimit()
                || (stream.isConnected() && !stream.channel().isWritable());
        if (holds) {
            if (pausing.add(stream) && pausing.size() == 1) {
                control.config().setAutoRead(false);
                liveness.pause();
            }
        } else if (pausing.remove(stream) && pausing.isEmpty()) {
            resumeControl();
        }
    }

    private void resumeControl() {
        if (control.isActive()) {
            control.config().setAutoRead(true);
            liveness.resume();
        }
    }

    // Connection handling.

    /** Gives up on a connection being made, or cuts one that is made. */
    private static void resetConnection(LocalRelayHandler stream) {
        if (stream.isConnected()) {
            resetConnection(stream.channel());
        } else {
            stream.cancelDial();
        }
    }

    /** Closes with a TCP {@code RST} rather than a {@code FIN}, dropping whatever is still queued. */
    private static void resetConnection(Channel channel) {
        if (channel.isOpen()) {
            channel.config().setOption(ChannelOption.SO_LINGER, 0);
        }
        channel.close();
    }

    /**
     * Shuts down the write side once everything written before is on the wire: Netty fails writes still queued when
     * the output shuts down, so shutting down at once would drop the end of the stream.
     */
    private static ChannelFuture shutdownOutputAfterWrites(DuplexChannel channel) {
        ChannelPromise shutdown = channel.newPromise();
        channel.writeAndFlush(Unpooled.EMPTY_BUFFER).addListener(done -> {
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
}
