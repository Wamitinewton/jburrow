package io.jburrow.protocol.flow;

/**
 * One stream's two directions: what this side may send on it, and what the peer may send. Created by
 * {@link SessionFlow#openStream()}, held in the stream's entry next to its lifecycle, and changed only through its
 * session.
 */
public final class StreamFlow {

    private final SendCredit send;
    private final ReceiveWindow receive;

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

    @Override
    public String toString() {
        return "StreamFlow[" + send + ", " + receive + "]";
    }
}
