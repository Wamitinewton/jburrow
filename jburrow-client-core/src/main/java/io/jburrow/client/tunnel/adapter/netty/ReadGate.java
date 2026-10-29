package io.jburrow.client.tunnel.adapter.netty;

import io.jburrow.client.tunnel.domain.ClientSession;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Decides when each local connection of one session is read, {@code docs/phase-2-multiplexing/ARCHITECTURE.md} §The
 * client: the server's read gate with "visitor" read as "local connection". A local connection is read only as far as
 * its stream can send right now:
 *
 * <pre>sendable = min(stream credit, session credit, control channel headroom, limits.maxFramePayload)</pre>
 *
 * <p>A local connection whose own send takes {@code sendable} to 0 is paused at once. The others are paused lazily, the
 * next time they are read: such a read gives up at least one byte, which the stream carries until credit returns. Only
 * connections that actually have data pay for a pause, and idle ones are never touched.
 *
 * <p>A connection blocked by its own stream's credit is resumed by that stream's {@code WINDOW_UPDATE}. One blocked by
 * the session's credit or headroom joins the line in {@link #blockedOnSession}, and every connection in line is resumed,
 * in line order, when stream 0's {@code WINDOW_UPDATE} arrives or the control channel becomes writable again. Neither
 * event touches connections that aren't waiting.
 *
 * <p>Streams share the session's credit. When the line is resumed, each stream in it may take at most an equal share of
 * the credit there is, for its first read, since NIO reads ready channels in no order the gate controls. The share
 * lifts once that read is sent or held, so credit a stream leaves unused goes to the others in later reads, and every
 * stream in line is resumed, so none waits while credit sits unused. A stream keeps its place in line, through any
 * number of resumes and one-byte pauses, until a read of its own is sent; then it leaves, and rejoins at the back if
 * that read spent what it may send.
 *
 * <p>A stream whose local connection is still being made is never read, and the gate ignores it until
 * {@link #onConnected}.
 *
 * <p>The control connection itself is never paused for flow control: credit is withheld instead, so {@code PING}s and
 * every other stream keep flowing.
 *
 * <p>Confined to the session's event loop, so a credit check and the read it allows happen with no gap between them.
 */
final class ReadGate {

    /** Why a local connection isn't read. */
    enum Wait {
        /** Its stream's credit ran out. */
        STREAM,
        /** The session's credit ran out. */
        SESSION,
        /** The control channel's outbound buffer is at its high watermark. */
        HEADROOM
    }

    /** What the gate asks of the connector when carried bytes can go out. */
    interface Output {

        /** Sends {@code bytes} as {@code DATA} on the stream, spending its credit. Takes over the buffer. */
        void sendCarried(LocalRelayHandler stream, ByteBuf bytes);

        /** Sends the {@code CLOSE} the local {@code FIN} was holding back until the carried bytes went out. */
        void sendDeferredClose(LocalRelayHandler stream);
    }

    private final ClientSession session;
    private final Channel control;
    private final Output output;
    /** The line: insertion order is the order streams joined it, which is the order they are resumed in. */
    private final Set<LocalRelayHandler> blockedOnSession = new LinkedHashSet<>();

    ReadGate(ClientSession session, Channel control, Output output) {
        this.session = Objects.requireNonNull(session, "session");
        this.control = Objects.requireNonNull(control, "control");
        this.output = Objects.requireNonNull(output, "output");
    }

    /**
     * How many bytes may be read from the stream's local connection and sent at once. 0 before the connection is made,
     * once this side has half-closed or closed the stream, and while the session is closing.
     */
    int sendable(LocalRelayHandler stream) {
        if (!stream.isConnected() || session.isClosing()) {
            return 0;
        }
        int credit = session.sendable(stream.streamId());
        if (credit == 0) {
            return 0;
        }
        int sendable = Math.min(Math.min(credit, headroom()), session.maxFramePayload());
        return stream.share() > 0 ? Math.min(sendable, stream.share()) : sendable;
    }

    /** The stream's local connection was made: read it if it may send. */
    void onConnected(LocalRelayHandler stream) {
        reassess(stream);
    }

    /**
     * A read from the local connection went out as {@code DATA}: the stream has had its turn and leaves the line. If
     * that spent what it may send, it stops being read now, and rejoins at the back if it waits for the session.
     */
    void afterSend(LocalRelayHandler stream) {
        blockedOnSession.remove(stream);
        stream.share(0);
        if (sendable(stream) == 0) {
            block(stream);
        }
    }

    /** The local connection was read past what it may send: carry {@code bytes}, send what fits, pause it for the rest. */
    void hold(LocalRelayHandler stream, ByteBuf bytes) {
        stream.share(0);
        stream.carry(bytes);
        reassess(stream);
    }

    /** A {@code WINDOW_UPDATE} on the stream: its carried bytes go out first, then it is read again. */
    void onStreamCredit(LocalRelayHandler stream) {
        reassess(stream);
    }

    /** A {@code WINDOW_UPDATE} on stream 0. */
    void onSessionCredit() {
        resumeBlockedOnSession();
    }

    void onControlWritable(boolean writable) {
        if (writable) {
            resumeBlockedOnSession();
        }
    }

    /** The stream ended: forget it, and release whatever it carried, which a reset doesn't send. */
    void onRemoved(LocalRelayHandler stream) {
        leaveLine(stream);
        stream.releaseCarry();
    }

    /**
     * Resumes the streams in line, in line order, each with an equal share of the credit for its first read and keeping
     * its place until a read of its own is sent. Stops once the session can't send again: the streams it hasn't reached
     * stay paused.
     */
    private void resumeBlockedOnSession() {
        if (blockedOnSession.isEmpty()) {
            return;
        }
        List<LocalRelayHandler> line = List.copyOf(blockedOnSession);
        int share = Math.max(1, sessionLimit() / line.size());
        for (LocalRelayHandler stream : line) {
            if (sessionLimit() == 0) {
                return;
            }
            if (blockedOnSession.contains(stream)) {
                stream.share(share);
                reassess(stream);
            }
        }
    }

    /** What every stream together may still send: the session's credit or the control channel's headroom. */
    private int sessionLimit() {
        return Math.min(session.sessionCredit(), headroom());
    }

    private void reassess(LocalRelayHandler stream) {
        if (!stream.isConnected()) {
            return;
        }
        if (stream.hasCarry()) {
            sendCarry(stream);
            if (stream.hasCarry()) {
                block(stream);
                return;
            }
            if (stream.takeCloseAfterCarry()) {
                leaveLine(stream);
                output.sendDeferredClose(stream);
                return;
            }
        }
        if (stream.channel().isInputShutdown()) {
            leaveLine(stream);
        } else if (sendable(stream) > 0) {
            leaveWait(stream);
            setAutoRead(stream, true);
        } else {
            block(stream);
        }
    }

    private void sendCarry(LocalRelayHandler stream) {
        for (int sendable = sendable(stream); sendable > 0 && stream.hasCarry(); sendable = sendable(stream)) {
            output.sendCarried(stream, stream.takeCarry(sendable));
        }
    }

    /**
     * Stops reading the local connection and records what it waits for. One waiting for the session joins the back of
     * the line, unless it already has a place.
     */
    private void block(LocalRelayHandler stream) {
        setAutoRead(stream, false);
        Wait wait = waitOf(stream);
        if (wait == null || wait == stream.waiting()) {
            return;
        }
        stream.waiting(wait);
        if (wait == Wait.STREAM) {
            blockedOnSession.remove(stream);
        } else {
            blockedOnSession.add(stream);
        }
    }

    /** What keeps the stream from sending; {@code null} if nothing credit-related does, as while the session closes. */
    private Wait waitOf(LocalRelayHandler stream) {
        if (session.isClosing()) {
            return null;
        }
        if (session.streamCredit(stream.streamId()) == 0) {
            return Wait.STREAM;
        }
        if (session.sessionCredit() == 0) {
            return Wait.SESSION;
        }
        return headroom() == 0 ? Wait.HEADROOM : null;
    }

    private static void leaveWait(LocalRelayHandler stream) {
        stream.waiting(null);
    }

    private void leaveLine(LocalRelayHandler stream) {
        blockedOnSession.remove(stream);
        leaveWait(stream);
    }

    /** Only when it changes, as on the server, where each change is a system call. */
    private static void setAutoRead(LocalRelayHandler stream, boolean autoRead) {
        if (stream.channel().config().isAutoRead() != autoRead) {
            stream.channel().config().setAutoRead(autoRead);
        }
    }

    /** How much more the control channel's outbound buffer takes before it passes its high watermark. */
    private int headroom() {
        return (int) Math.min(Integer.MAX_VALUE, control.bytesBeforeUnwritable());
    }

    /** The line of streams waiting for session credit or headroom, in the order they will be resumed. */
    List<LocalRelayHandler> blockedOnSession() {
        return List.copyOf(blockedOnSession);
    }
}
