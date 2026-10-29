package io.jburrow.protocol.flow;

/**
 * One stream's two directions: what this side may send on it, and what the peer may send. Created by
 * {@link SessionFlow#openStream()}, held in the stream's entry next to its lifecycle, and changed only through its
 * session.
 */
public final class StreamFlow {

    private final SendCredit send;
    private final ReceiveWindow receive;
    private boolean removed;

    StreamFlow(SendCredit send, ReceiveWindow receive) {
        this.send = send;
        this.receive = receive;
    }

    public SendCredit send() {
        return send;
    }

    public ReceiveWindow receive() {
        return receive;
    }

    /** Send credit taken by {@link SessionFlow#reserve} and not yet sent or refunded. */
    public int reserved() {
        return send.reserved();
    }

    /** Received bytes still held whose session credit {@link SessionFlow#park} returned early. */
    public int parked() {
        return receive.parked();
    }

    /** True once {@link SessionFlow#onRemoved} has seen the stream leave its table. */
    boolean isRemoved() {
        return removed;
    }

    void markRemoved() {
        removed = true;
    }

    @Override
    public String toString() {
        return "StreamFlow[" + send + ", " + receive + "]";
    }
}
