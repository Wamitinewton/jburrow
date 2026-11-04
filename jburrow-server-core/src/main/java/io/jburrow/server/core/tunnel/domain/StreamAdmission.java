package io.jburrow.server.core.tunnel.domain;

/**
 * Admitting a visitor connection to a stream, which is checked in three steps: the session
 * ({@link Session#checkAdmission}), then the server-wide stream quota, then the allocation
 * ({@link Session#openStream}). A refused visitor is closed at once; the client is not told.
 */
public final class StreamAdmission {

    private StreamAdmission() {}

    /** Why a visitor connection was refused. {@link #label()} is the metric label. */
    public enum Refusal {
        NOT_ESTABLISHED("not_established"),
        /** The server is stopping: the session finishes its open streams and takes no new one. */
        DRAINING("draining"),
        STREAM_LIMIT("stream_limit"),
        SERVER_STREAM_LIMIT("server_stream_limit");

        private final String label;

        Refusal(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }
}
