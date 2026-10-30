package io.jburrow.server.core.tunnel.adapter.netty.tcp;

import io.jburrow.protocol.adapter.netty.Frame;
import io.jburrow.protocol.wire.StreamIds;
import io.jburrow.server.core.tunnel.adapter.netty.control.SessionStreams;
import io.jburrow.server.core.tunnel.adapter.netty.http.SessionExchanges;
import java.util.Objects;

/**
 * A session's streams of both kinds: TCP visitors through its {@link StreamRelay}, HTTP exchanges through its
 * {@link SessionExchanges}. A frame on a stream goes to whichever holds the stream; one on a stream neither holds is
 * the relay's, which asks the session and finds it closed. Stream 0's credit is counted once, by the relay, and then
 * offered to both.
 *
 * <p>Confined to the session's event loop.
 */
final class TunnelStreams implements SessionStreams {

    private final StreamRelay tcp;
    private final SessionExchanges http;

    TunnelStreams(StreamRelay tcp, SessionExchanges http) {
        this.tcp = Objects.requireNonNull(tcp, "tcp");
        this.http = Objects.requireNonNull(http, "http");
    }

    @Override
    public void onData(Frame.Data data) {
        if (http.owns(data.streamId())) {
            http.onData(data);
        } else {
            tcp.onData(data);
        }
    }

    /** The session tells an exchange's stream from a visitor's, and refuses a head on the latter. */
    @Override
    public void onHeaders(Frame.Headers headers) {
        http.onHeaders(headers);
    }

    @Override
    public void onClose(Frame.Close close) {
        if (http.owns(close.streamId())) {
            http.onClose(close);
        } else {
            tcp.onClose(close);
        }
    }

    @Override
    public void onWindowUpdate(Frame.WindowUpdate update) {
        if (update.streamId() == StreamIds.CONTROL) {
            tcp.onWindowUpdate(update);
            http.onSessionCredit();
        } else if (http.owns(update.streamId())) {
            http.onWindowUpdate(update);
        } else {
            tcp.onWindowUpdate(update);
        }
    }

    @Override
    public void onReadComplete() {
        tcp.onReadComplete();
        http.onReadComplete();
    }

    @Override
    public void onSessionWritabilityChanged(boolean writable) {
        tcp.onSessionWritabilityChanged(writable);
        http.onSessionWritabilityChanged(writable);
    }

    @Override
    public void cancelAll() {
        tcp.cancelAll();
        http.cancelAll();
    }

    StreamRelay relay() {
        return tcp;
    }

    @Override
    public String describeFlow() {
        return tcp.describeFlow() + "\n" + http.describeFlow();
    }
}
