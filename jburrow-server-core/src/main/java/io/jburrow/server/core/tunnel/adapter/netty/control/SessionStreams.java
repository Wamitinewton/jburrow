package io.jburrow.server.core.tunnel.adapter.netty.control;

import io.jburrow.protocol.adapter.netty.Frame;

/**
 * The streams of one session, as its control handler sees them: where the client's {@code HEADERS}, {@code DATA},
 * {@code CLOSE} and {@code WINDOW_UPDATE} frames go, and what happens to them when the session's connection changes. A
 * session's streams carry TCP visitors and HTTP exchanges side by side. The tunnel listeners provide it when they bind,
 * and store it with {@link SessionContext#streams(SessionStreams)}.
 *
 * <p>Every method is called on the session's event loop.
 */
public interface SessionStreams {

    /** A {@code DATA} frame from the client. Takes over the frame's buffer, on every path. */
    void onData(Frame.Data data);

    /**
     * A response head from the client, in a session with {@code http}.
     *
     * @throws io.jburrow.protocol.wire.ProtocolException {@code PROTOCOL_ERROR} or {@code VALIDATION_FAILED}, which end
     *     the session
     */
    void onHeaders(Frame.Headers headers);

    /** A {@code CLOSE} or {@code CLOSE RST} frame from the client. */
    void onClose(Frame.Close close);

    /**
     * A {@code WINDOW_UPDATE} from the client, on stream 0 or a data stream: senders it unblocks resume.
     *
     * @throws io.jburrow.protocol.wire.ProtocolException {@code FLOW_CONTROL_ERROR} or {@code PROTOCOL_ERROR}, which
     *     end the session
     */
    void onWindowUpdate(Frame.WindowUpdate update);

    /** The session's connection finished a read batch: flush what the batch wrote towards visitors. */
    void onReadComplete();

    /** The session's connection crossed one of its write-buffer watermarks. */
    void onSessionWritabilityChanged(boolean writable);

    /**
     * Resets every open stream because the session is ending: {@code CLOSE RST CANCELLED} for each while the
     * connection still works, then each visitor connection closed with a TCP {@code RST}. Safe to call more than once.
     */
    void cancelAll();

    /**
     * The flow state of the session and each open stream, for diagnosing a stall: credits and windows both ways, what
     * each visitor waits for and carries, and the line waiting for session credit. Never a payload byte.
     */
    String describeFlow();
}
