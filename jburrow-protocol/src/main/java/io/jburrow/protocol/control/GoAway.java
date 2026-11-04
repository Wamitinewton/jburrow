package io.jburrow.protocol.control;

/**
 * {@code GOAWAY}, server to client, with {@code resume}: the server is draining. The client waits at least
 * {@code reconnectAfterMs} after the connection closes before connecting again; the server keeps the session open for
 * its open streams for at most {@code drainMs}, and opens no new stream on it.
 */
public record GoAway(long reconnectAfterMs, long drainMs) implements ControlMessage {

    public GoAway {
        Fields.range(reconnectAfterMs, 0, Fields.MAX_RECONNECT_AFTER_MS, "reconnectAfterMs");
        Fields.range(drainMs, 0, Fields.MAX_DRAIN_MS, "drainMs");
    }
}
