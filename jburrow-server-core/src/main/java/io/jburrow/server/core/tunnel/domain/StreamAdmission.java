package io.jburrow.server.core.tunnel.domain;

/** Whether a visitor connection gets a stream. */
public sealed interface StreamAdmission {

    /** Send {@code OPEN} on {@code streamId}. */
    record Admitted(int streamId) implements StreamAdmission {}

    /** Close the visitor connection at once; the client is not told. */
    record Refused(Refusal reason) implements StreamAdmission {}

    /** Why a visitor connection was refused. {@link #label()} is the metric label. */
    enum Refusal {
        NOT_ESTABLISHED("not_established"),
        STREAM_LIMIT("stream_limit");

        private final String label;

        Refusal(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }
}
