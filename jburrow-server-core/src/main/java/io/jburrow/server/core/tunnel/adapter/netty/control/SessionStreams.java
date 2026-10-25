package io.jburrow.server.core.tunnel.adapter.netty.control;

import io.jburrow.protocol.adapter.netty.Frame;

/**
 * The streams of one session, as its control handler sees them: where the client's {@code DATA} and {@code CLOSE}
 * frames go, and what happens to them when the session's connection changes. The tunnel listeners provide it when
 * they bind, and store it with {@link SessionContext#streams(SessionStreams)}.
 *
 * <p>Every method is called on the session's event loop.
 */
public interface SessionStreams {

    /** A {@code DATA} frame from the client. Takes over the frame's buffer, on every path. */
    void onData(Frame.Data data);

    /** A {@code CLOSE} or {@code CLOSE RST} frame from the client. */
    void onClose(Frame.Close close);

    /** The session's connection finished a read batch: flush what the batch wrote towards visitors. */
    void onReadComplete();

    /** The session's connection crossed one of its write-buffer watermarks. */
    void onSessionWritabilityChanged(boolean writable);

    /**
     * Resets every open stream because the session is ending: {@code CLOSE RST CANCELLED} for each while the
     * connection still works, then each visitor connection closed with a TCP {@code RST}. Safe to call more than once.
     */
    void cancelAll();
}
