package io.jburrow.client.tunnel.adapter.netty;

import io.jburrow.client.tunnel.domain.ActiveTunnel;
import io.jburrow.client.tunnel.domain.ClientSession;
import io.jburrow.client.tunnel.domain.LocalTarget;
import io.jburrow.protocol.adapter.netty.CreditBoundedRecvByteBufAllocator;
import io.jburrow.protocol.adapter.netty.Frame;
import io.jburrow.protocol.control.RemoteAddress;
import io.jburrow.protocol.flow.Credit;
import io.jburrow.protocol.flow.DataOutcome;
import io.jburrow.protocol.stream.StreamEvent;
import io.jburrow.protocol.stream.StreamOutcome;
import io.jburrow.protocol.stream.StreamState;
import io.jburrow.protocol.wire.ErrorCode;
import io.jburrow.protocol.wire.StreamIds;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFactory;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPromise;
import io.netty.channel.socket.DuplexChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.util.concurrent.Future;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
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
 * <p>Flow control ({@code docs/phase-2-multiplexing/ARCHITECTURE.md} §The client): a local connection is read only as
 * far as its stream may send, which the session's {@link ReadGate} decides, and the server's {@code DATA} returns its
 * credit only once its write to the local connection has completed, successfully or not. A local service that stops
 * reading so holds back only its own stream, and the control connection is always read.
 *
 * <p>Opening is optimistic (§9.4): {@code DATA} that arrives while a local connection is being made is held, with no
 * limit but the client's stream window, which the server can't pass until that data is written and released. If the
 * connection fails, the held data is released and its session credit returned.
 *
 * <p>Writes are flushed once per read batch, never per frame: towards the server when a local connection's read batch
 * completes, towards local connections when the control connection's does.
 *
 * <p>Every local connection is registered on the session's event loop, so all of this is confined to one thread.
 */
final class LocalConnector {

    static final int CONNECT_TIMEOUT_MILLIS = 10_000;

    private static final Logger log = LoggerFactory.getLogger(LocalConnector.class);

    /** Handlers are added to a local connection once it is made, so it starts with none. */
    private static final ChannelInitializer<Channel> NO_HANDLERS = new ChannelInitializer<>() {
        @Override
        protected void initChannel(Channel ch) {}
    };

    private final ControlConnection connection;
    private final ClientSession session;
    private final Channel control;
    private final LocalDial dial;
    private final LongSupplier nanoTime;
    private final ReadGate gate;
    private final Map<Integer, LocalRelayHandler> streams = new LinkedHashMap<>();
    private final List<LocalRelayHandler> written = new ArrayList<>();

    /** Carried bytes went out outside a local connection's read batch, and wait for the control channel's next flush. */
    private boolean controlFlushPending;

    /**
     * Makes the connection to a stream's local target. The future completes on the session's event loop with a
     * {@link DuplexChannel} that isn't reading yet ({@code autoRead} off) and has no handlers.
     */
    @FunctionalInterface
    interface LocalDial {
        Future<Channel> dial(LocalTarget target);
    }

    LocalConnector(ControlConnection connection, Channel control, Dialer dialer, LongSupplier nanoTime) {
        this(connection, control, tcp(dialer, control), nanoTime);
    }

    LocalConnector(ControlConnection connection, Channel control, LocalDial dial, LongSupplier nanoTime) {
        this.connection = Objects.requireNonNull(connection, "connection");
        this.session = connection.session();
        this.control = Objects.requireNonNull(control, "control");
        this.dial = Objects.requireNonNull(dial, "dial");
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
        this.gate = new ReadGate(session, control, new CarriedOutput());
    }

    /** TCP connections on the control connection's event loop, so every stream is confined to the session's thread. */
    private static LocalDial tcp(Dialer dialer, Channel control) {
        Objects.requireNonNull(dialer, "dialer");
        Bootstrap bootstrap = new Bootstrap()
                .group(control.eventLoop())
                .channelFactory((ChannelFactory<NioSocketChannel>) NioSocketChannel::new)
                .option(ChannelOption.TCP_NODELAY, true)
                .option(ChannelOption.ALLOW_HALF_CLOSURE, true)
                .option(ChannelOption.AUTO_READ, false)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, CONNECT_TIMEOUT_MILLIS)
                .handler(NO_HANDLERS);
        return target -> dialer.dial(bootstrap, control.eventLoop(), target.host(), target.port());
    }

    ReadGate gate() {
        return gate;
    }

    // Server to local target.

    /** The server opened a stream: connect it to its tunnel's local target. */
    void open(Frame.Open open) {
        int streamId = open.streamId();
        ActiveTunnel tunnel = session.onOpen(streamId, open.message());
        LocalRelayHandler stream = new LocalRelayHandler(this, streamId, tunnel, nanoTime.getAsLong());
        streams.put(streamId, stream);
        RemoteAddress remote = open.message().remote();
        log.debug(
                "stream opened: stream={} visitor={}:{} local={}",
                streamId,
                remote.address(),
                remote.port(),
                tunnel.target());
        connection.tell(events -> events.onStreamOpened(streamId, tunnel.ref(), remote.address(), remote.port()));
        Future<Channel> dialing = dial.dial(tunnel.target());
        stream.dialing(dialing);
        dialing.addListener(done -> onDialed(stream, dialing));
    }

    /**
     * The server's {@code DATA}. Takes over the frame's buffer. A delivered payload is held against the windows until
     * its write to the local connection completes, successfully or not; only then does its credit go back. While the
     * connection is being made, it waits in the stream's queue.
     */
    void onData(Frame.Data data) {
        int streamId = data.streamId();
        int length = data.payload().readableBytes();
        DataOutcome outcome;
        try {
            outcome = session.onDataReceived(streamId, length);
        } catch (RuntimeException e) {
            data.release();
            throw e;
        }
        switch (outcome) {
            case DataOutcome.Deliver deliver -> {
                LocalRelayHandler stream = require(streamId);
                stream.countIn(length);
                if (stream.isConnected()) {
                    deliver(stream, data.payload());
                    if (stream.markFlushPending()) {
                        written.add(stream);
                    }
                } else {
                    stream.hold(data.payload());
                }
            }
            case DataOutcome.Reset reset -> {
                data.release();
                sendCredit(streamId, reset.credit());
                resetFromServerFault(require(streamId), reset.code());
            }
            case DataOutcome.Discard discarded -> {
                data.release();
                sendCredit(streamId, discarded.credit());
            }
        }
    }

    /** Credit from the server: the streams it unblocks are read again, their carried bytes first. */
    void onWindowUpdate(Frame.WindowUpdate update) {
        switch (session.onWindowUpdate(update.streamId(), update.increment())) {
            case SESSION -> gate.onSessionCredit();
            case STREAM -> gate.onStreamCredit(require(update.streamId()));
            case IGNORED, DISCARDED -> {}
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
        flushCarried();
        if (written.isEmpty()) {
            return;
        }
        for (LocalRelayHandler stream : written) {
            stream.flush();
        }
        written.clear();
    }

    /** Headroom on the control channel is one term of what a stream may send; nothing else reacts to writability. */
    void onControlWritabilityChanged(boolean writable) {
        if (!session.isClosing()) {
            gate.onControlWritable(writable);
            flushCarried();
        }
    }

    /** A payload's write to its local connection completed or failed: it is no longer held, so its credit goes back. */
    private void onDelivered(int streamId, int bytes) {
        sendCredit(streamId, session.onReleased(streamId, bytes));
    }

    /**
     * Sends the {@code WINDOW_UPDATE}s {@code credit} names, each flushed at once: updates are rare, one per half
     * window. Nothing goes to a session that is closing, whose credit no longer matters.
     */
    private void sendCredit(int streamId, Credit credit) {
        if (credit.isEmpty() || !control.isActive() || session.isClosing()) {
            return;
        }
        if (credit.session() > 0) {
            control.writeAndFlush(new Frame.WindowUpdate(StreamIds.CONTROL, credit.session()), control.voidPromise());
        }
        if (credit.stream() > 0) {
            control.writeAndFlush(new Frame.WindowUpdate(streamId, credit.stream()), control.voidPromise());
        }
    }

    // Local target to server.

    /**
     * Takes over {@code bytes}. A read the gate sized is sent whole, as one {@code DATA} no longer than the server
     * allows (§6.5). A read it couldn't size, one byte with nothing sendable, goes behind whatever the stream already
     * carries, and out as credit returns.
     */
    void onLocalData(LocalRelayHandler stream, ByteBuf bytes) {
        if (!isOpen(stream) || session.isClosing() || !bytes.isReadable()) {
            bytes.release();
            return;
        }
        if (stream.hasCarry() || bytes.readableBytes() > gate.sendable(stream)) {
            gate.hold(stream, bytes);
            return;
        }
        sendData(stream, bytes);
        gate.afterSend(stream);
    }

    /** Spends the stream's and the session's credit, then writes, unflushed. Takes over {@code bytes}. */
    private void sendData(LocalRelayHandler stream, ByteBuf bytes) {
        int streamId = stream.streamId();
        int length = bytes.readableBytes();
        try {
            session.onDataSent(streamId, length);
        } catch (RuntimeException e) {
            bytes.release();
            throw e;
        }
        stream.countOut(length);
        control.write(new Frame.Data(streamId, bytes), control.voidPromise());
    }

    void onLocalReadComplete() {
        controlFlushPending = false;
        control.flush();
    }

    /**
     * The local target sent {@code FIN}: half-close the stream, and finish it if the server had closed its half. A
     * half-close follows the stream's last byte, so while bytes are carried it waits for them.
     */
    void onLocalInputShutdown(LocalRelayHandler stream) {
        if (!isOpen(stream) || session.isClosing()) {
            return;
        }
        if (stream.hasCarry()) {
            stream.closeAfterCarry();
            return;
        }
        halfClose(stream);
    }

    private void halfClose(LocalRelayHandler stream) {
        int streamId = stream.streamId();
        session.onStreamSent(streamId, StreamEvent.CLOSE_SENT);
        control.writeAndFlush(Frame.Close.half(streamId), control.voidPromise());
        if (session.streamState(streamId).isEmpty()) {
            finish(stream, Optional.empty(), "closed");
            closeAfterWrites(stream.channel());
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
        // autoRead is off until the gate turns it on, and Netty makes a channel's read handle on its first read, so
        // this allocator sizes every read.
        channel.config().setRecvByteBufAllocator(new CreditBoundedRecvByteBufAllocator(() -> gate.sendable(stream)));
        stream.connected(channel);
        channel.pipeline().addLast(stream);
        writePending(stream);
        if (stream.isShutdownOutputOnConnect()) {
            shutdownOutputAfterWrites(channel);
        }
        gate.onConnected(stream);
    }

    /** Writes what arrived while the connection was being made, in order, each releasing its credit once written. */
    private void writePending(LocalRelayHandler stream) {
        for (ByteBuf payload = stream.pollPending(); payload != null; payload = stream.pollPending()) {
            deliver(stream, payload);
        }
        stream.channel().flush();
    }

    /** Writes a payload to the stream's local connection, unflushed. Its credit goes back when the write completes. */
    private void deliver(LocalRelayHandler stream, ByteBuf payload) {
        int streamId = stream.streamId();
        int length = payload.readableBytes();
        ChannelPromise delivered = stream.channel().newPromise();
        delivered.addListener(written -> onDelivered(streamId, length));
        stream.channel().write(payload, delivered);
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

    /**
     * Forgets a stream the session has closed, and reports it. What it still held, waiting for its connection, is
     * released into the flow, so its session credit comes back.
     */
    private void finish(LocalRelayHandler stream, Optional<ErrorCode> reset, String ending) {
        streams.remove(stream.streamId());
        written.remove(stream);
        gate.onRemoved(stream);
        releasePending(stream);
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

    private void releasePending(LocalRelayHandler stream) {
        for (ByteBuf payload = stream.pollPending(); payload != null; payload = stream.pollPending()) {
            int length = payload.readableBytes();
            payload.release();
            sendCredit(stream.streamId(), session.onReleased(stream.streamId(), length));
        }
    }

    private void flushCarried() {
        if (controlFlushPending) {
            controlFlushPending = false;
            control.flush();
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

    /** Where the gate's carried bytes, and the {@code CLOSE} that waited for them, go out. */
    private final class CarriedOutput implements ReadGate.Output {

        @Override
        public void sendCarried(LocalRelayHandler stream, ByteBuf bytes) {
            sendData(stream, bytes);
            controlFlushPending = true;
        }

        @Override
        public void sendDeferredClose(LocalRelayHandler stream) {
            halfClose(stream);
        }

        @Override
        public void flushCarried() {
            LocalConnector.this.flushCarried();
        }
    }
}
