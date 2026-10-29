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
 * <p>Two extensions serve a sender and a receiver that can't consult this model at the moment they act. A
 * {@link #reserve reservation} takes send credit before a read whose size isn't known yet, and {@link #refund} gives
 * back what the read didn't use. {@link #park Parking} returns a stalled stream's held bytes to the session window
 * early, without returning its stream credit, so a stream that has stopped draining stops holding back the others.
 * Neither changes the wire: the peer can't tell a reserved byte from one not yet read, and parking is credit returned
 * before release, which the protocol allows.
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
    private long parked;

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

    /**
     * The most {@code DATA} this side may send on {@code stream} right now: the smaller of its two credits. Credit
     * already reserved, by this stream or another, doesn't count.
     */
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
     * Reserves up to {@code max} bytes of the stream's credit and the session's, for a read that sends what it gets with
     * {@link #onSentReserved} and gives the rest back with {@link #refund}. The caller folds any limit that isn't flow
     * state, such as the session channel's headroom, into {@code max}.
     *
     * @return the bytes reserved: {@code min(max, sendable(stream))}, 0 if there is no credit or {@code max} is 0
     * @throws IllegalStateException if the stream has been {@linkplain #onRemoved removed}
     */
    public int reserve(StreamFlow stream, int max) {
        if (max < 0) {
            throw new IllegalArgumentException("A reservation asks for at least 0 bytes, not " + max);
        }
        requireInTable(stream, "Reserving for");
        int bytes = Math.min(max, sendable(stream));
        if (bytes > 0) {
            stream.send().reserve(bytes);
            sessionSend.reserve(bytes);
        }
        return bytes;
    }

    /**
     * Sends {@code DATA} of {@code bytes} out of the stream's reservation, so no credit is taken again.
     *
     * @throws IllegalStateException if {@code bytes} is more than the stream has reserved
     */
    public void onSentReserved(StreamFlow stream, int bytes) {
        stream.send().checkReserved("Sending", bytes);
        sessionSend.checkReserved("Sending", bytes);
        stream.send().spendReserved(bytes);
        sessionSend.spendReserved(bytes);
    }

    /**
     * Gives {@code bytes} of the stream's reservation back to its credit and the session's. A refund of 0, a read that
     * used its whole reservation, changes nothing.
     *
     * @throws IllegalStateException if {@code bytes} is more than the stream has reserved
     */
    public void refund(StreamFlow stream, int bytes) {
        if (bytes == 0) {
            return;
        }
        stream.send().checkReserved("Refunding", bytes);
        sessionSend.checkReserved("Refunding", bytes);
        stream.send().refund(bytes);
        sessionSend.refund(bytes);
    }

    /**
     * Refunds everything the stream still has reserved, for a stream that has ended. The session's part is what matters,
     * since no other stream could send with it until it came back. A stream with nothing reserved changes nothing.
     */
    public void refundAll(StreamFlow stream) {
        refund(stream, stream.reserved());
    }

    /** Send credit reserved on all streams together. */
    public int reserved() {
        return sessionSend.reserved();
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
     * <p>A stream's parked bytes are released first, since they arrived first. For those only the stream window
     * releases: their session credit went back when they were parked. For the rest, both windows release.
     *
     * @param stream the stream the bytes arrived on, or {@code null} once its entry is gone and it has nothing parked;
     *     then only session credit comes back. A stream that leaves its table with bytes parked is passed in, after
     *     {@link #onRemoved}, until it holds nothing.
     * @return the {@code WINDOW_UPDATE}s to send now
     * @throws IllegalStateException if more is released than is held
     */
    public Credit onReleased(StreamFlow stream, int bytes) {
        if (stream == null) {
            sessionReceive.checkRelease(bytes);
            return Credit.of(sessionReceive.release(bytes), 0);
        }
        stream.receive().checkRelease(bytes);
        int unparked = bytes - Math.min(bytes, stream.parked());
        if (unparked > 0) {
            sessionReceive.checkRelease(unparked);
        }
        parked -= bytes - unparked;
        int session = unparked == 0 ? 0 : sessionReceive.release(unparked);
        int streamIncrement = stream.receive().release(bytes);
        return Credit.of(session, streamIncrement);
    }

    /**
     * Treats every byte the stream holds and hasn't parked as released for the session window only, so their session
     * credit can go back now while they wait for their destination. The stream's own credit comes back only as they
     * are actually released, so a parked stream never holds more than its stream window. Whether and when to park is
     * the caller's policy.
     *
     * @return the session credit to send now; {@link Credit#NONE} if nothing new was parked
     * @throws IllegalStateException if the stream has been {@linkplain #onRemoved removed}
     */
    public Credit park(StreamFlow stream) {
        requireInTable(stream, "Parking");
        int parking = stream.receive().held() - stream.parked();
        if (parking == 0) {
            return Credit.NONE;
        }
        sessionReceive.checkRelease(parking);
        stream.receive().park();
        parked += parking;
        return Credit.of(sessionReceive.release(parking), 0);
    }

    /** Bytes parked on all streams together, still held. */
    public long parked() {
        return parked;
    }

    /**
     * The stream has left its table, closed or reset: no more stream credit goes back on it, and it can't reserve or
     * park. What it still holds is released later as usual, by passing it to {@link #onReleased}, or {@code null} if it
     * has nothing parked. A reservation it still has is {@link #refundAll}'s.
     */
    public void onRemoved(StreamFlow stream) {
        stream.receive().stopReturning();
        stream.markRemoved();
    }

    private static void requireInTable(StreamFlow stream, String action) {
        if (stream.isRemoved()) {
            throw new IllegalStateException(action + " a stream that has left its table: " + stream);
        }
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
        return "SessionFlow[" + sessionSend + ", " + sessionReceive + (parked == 0 ? "" : ", parked=" + parked) + "]";
    }
}
