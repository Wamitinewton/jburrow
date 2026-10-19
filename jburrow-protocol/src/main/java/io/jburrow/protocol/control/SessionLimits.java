package io.jburrow.protocol.control;

/**
 * The server's terms for one session, sent in its {@code HELLO}. The client stays within them and the server enforces
 * them. {@code maxFramePayload} may only lower the protocol's 65,536-byte {@code DATA} cap.
 */
public record SessionLimits(
        int maxFramePayload, int maxTunnels, int maxConcurrentStreams, long pingIntervalMs, long idleTimeoutMs) {

    public SessionLimits {
        Fields.range(maxFramePayload, 1, Fields.MAX_FRAME_PAYLOAD, "maxFramePayload");
        Fields.range(maxTunnels, 1, Integer.MAX_VALUE, "maxTunnels");
        Fields.range(maxConcurrentStreams, 1, Integer.MAX_VALUE, "maxConcurrentStreams");
        if (pingIntervalMs < Fields.MIN_PING_INTERVAL_MS) {
            throw Fields.invalid("pingIntervalMs", "must be at least " + Fields.MIN_PING_INTERVAL_MS);
        }
        if (idleTimeoutMs / 2 < pingIntervalMs) {
            throw Fields.invalid("idleTimeoutMs", "must be at least twice pingIntervalMs");
        }
    }
}
