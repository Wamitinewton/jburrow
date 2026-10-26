package io.jburrow.protocol.flow;

import io.jburrow.protocol.wire.ErrorCode;
import io.jburrow.protocol.wire.ProtocolException;

/**
 * One session's flow control: the session-level credit in both directions, and the windows every new stream starts
 * with. "Local" windows are this side's, announced in its {@code HELLO}, and bound what the peer may send here;
 * "remote" windows are the peer's, and bound what this side may send.
 *
 * <p>The session doesn't keep its streams: each side's stream table holds the {@link StreamFlow} next to the stream's
 * lifecycle and passes it in. A call that throws changes nothing.
 *
 * <p>Not thread-safe. Confined to the thread that owns its session.
 */
public final class SessionFlow {

    private static final String STREAM = "stream";
    private static final String SESSION = "session";

    private final int localStreamWindow;
    private final int remoteStreamWindow;
    private final SendCredit sessionSend;
    private final ReceiveWindow sessionReceive;

    /**
     * @throws IllegalArgumentException if a window is below 1 byte, or a session window is smaller than its stream
     *     window; a {@code HELLO} that announces either is rejected before it gets here
     */
    public SessionFlow(int localStream, int localSession, int remoteStream, int remoteSession) {
        requireWindows("local", localStream, localSession);
        requireWindows("remote", remoteStream, remoteSession);
        this.localStreamWindow = localStream;
        this.remoteStreamWindow = remoteStream;
        this.sessionSend = new SendCredit(SESSION, remoteSession);
        this.sessionReceive = new ReceiveWindow(SESSION, localSession);
    }

    private static void requireWindows(String side, int stream, int session) {
        if (stream < 1 || session < stream) {
            throw new IllegalArgumentException("The " + side + " windows need 1 <= stream <= session, not stream "
                    + stream + " and session " + session);
        }
    }

    /** The flow for a new stream: the peer's stream window to send into, this side's to receive into. */
    public StreamFlow openStream() {
        return new StreamFlow(new SendCredit(STREAM, remoteStreamWindow), new ReceiveWindow(STREAM, localStreamWindow));
    }

    /** What this side may still send on all streams together. */
    public SendCredit sessionSend() {
        return sessionSend;
    }

    /** What the peer may still send on all streams together, and the session credit this side owes it. */
    public ReceiveWindow sessionReceive() {
        return sessionReceive;
    }

    /** The most {@code DATA} this side may send on {@code stream} right now: the smaller of its two credits. */
    public int sendable(StreamFlow stream) {
        return (int) Math.min(stream.send().available(), sessionSend.available());
    }

    /**
     * Takes {@code bytes} from the stream's credit and the session's.
     *
     * @throws IllegalStateException if {@code bytes} is more than {@link #sendable}: a bug in the caller
     */
    public void onSent(StreamFlow stream, int bytes) {
        if (bytes < 1) {
            throw new IllegalArgumentException("DATA carries at least 1 byte, not " + bytes);
        }
        int sendable = sendable(stream);
        if (bytes > sendable) {
            throw new IllegalStateException("Sending " + bytes + " bytes exceeds the " + sendable
                    + " this stream may send (stream credit " + stream.send().available() + ", session credit "
                    + sessionSend.available() + ")");
        }
        stream.send().spend(bytes);
        sessionSend.spend(bytes);
    }

    /**
     * A {@code WINDOW_UPDATE} on stream 0.
     *
     * @throws ProtocolException {@link ErrorCode#FLOW_CONTROL_ERROR} if the session credit would pass 2^31 − 1
     */
    public void onSessionUpdate(int increment) {
        sessionSend.grant(increment);
    }

    /**
     * A {@code WINDOW_UPDATE} on a data stream, which the stream's lifecycle accepted with credit added.
     *
     * @throws ProtocolException {@link ErrorCode#FLOW_CONTROL_ERROR} if the stream credit would pass 2^31 − 1
     */
    public void onStreamUpdate(StreamFlow stream, int increment) {
        stream.send().grant(increment);
    }

    /**
     * {@code DATA} the stream's lifecycle accepted. The session window is checked first, then the stream's, as the
     * specification orders them.
     *
     * @throws ProtocolException {@link ErrorCode#FLOW_CONTROL_ERROR} if {@code bytes} overruns either window
     */
    public void onReceived(StreamFlow stream, int bytes) {
        sessionReceive.checkReceive(bytes);
        stream.receive().checkReceive(bytes);
        sessionReceive.receive(bytes);
        stream.receive().receive(bytes);
    }

    /**
     * {@code DATA} for a stream that is closed, or that the lifecycle reset: it still counts against the session
     * window, and is released at once so its session credit comes back. Without this, every reset would leak session
     * credit until the session stalled.
     *
     * @return the session credit to send now, if any
     * @throws ProtocolException {@link ErrorCode#FLOW_CONTROL_ERROR} if {@code bytes} overruns the session window
     */
    public Credit onDiscarded(int bytes) {
        sessionReceive.receive(bytes);
        return Credit.of(sessionReceive.release(bytes), 0);
    }

    /**
     * Bytes this side no longer holds: written to their destination, successfully or not, or dropped. Each received
     * byte is released exactly once.
     *
     * @param stream the stream the bytes arrived on, or {@code null} once its entry is gone; then only session credit
     *     comes back
     * @return the {@code WINDOW_UPDATE}s to send now
     * @throws IllegalStateException if more is released than is held
     */
    public Credit onReleased(StreamFlow stream, int bytes) {
        sessionReceive.checkRelease(bytes);
        if (stream != null) {
            stream.receive().checkRelease(bytes);
        }
        int session = sessionReceive.release(bytes);
        int streamIncrement = stream == null ? 0 : stream.receive().release(bytes);
        return Credit.of(session, streamIncrement);
    }

    /**
     * The peer's {@code CLOSE} arrived on {@code stream}: it sends no more {@code DATA} there, so no further stream
     * credit goes back. Its released bytes still return session credit.
     */
    public void onPeerFinished(StreamFlow stream) {
        stream.receive().stopReturning();
    }

    @Override
    public String toString() {
        return "SessionFlow[" + sessionSend + ", " + sessionReceive + "]";
    }
}
