package io.jburrow.server.core.tunnel.adapter.netty.http;

import static io.jburrow.server.core.tunnel.adapter.netty.http.HttpHarness.TIMEOUT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import io.jburrow.protocol.adapter.netty.Frame;
import io.jburrow.protocol.control.BodyFraming;
import io.jburrow.protocol.control.HeaderFields;
import io.jburrow.protocol.control.HttpResponseHead;
import io.jburrow.protocol.control.RegisteredTunnel;
import io.jburrow.protocol.testsupport.ScriptedPeer;
import io.jburrow.server.core.tunnel.adapter.netty.control.SessionContext;
import io.jburrow.server.core.tunnel.adapter.netty.edge.EdgeTransport;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * {@code docs/phase-3-http-tunnels/ARCHITECTURE.md} §Concurrency model: the two halves of an exchange each refuse to be
 * touched from the other's loop, and every call into the session's flow state goes through one of the session half's
 * checked entries. An exchange that crosses between two loops in both directions therefore completes only if none of
 * its calls reached the session's state from the edge's loop.
 */
class ExchangeLoopConfinementTest {

    private static final String TRANSPORTS = "io.jburrow.server.core.tunnel.adapter.netty.support.Transports#available";

    private HttpHarness harness;

    @AfterEach
    void stop() throws Exception {
        if (harness != null) {
            harness.close();
        }
    }

    @ParameterizedTest
    @MethodSource(TRANSPORTS)
    void anUploadAndItsResponseCrossBetweenTwoLoopsWithEveryCallOnItsOwn(EdgeTransport transport) throws Exception {
        harness = new HttpHarness(HttpHarness.Settings.defaults(transport));
        ScriptedPeer peer = harness.connect();
        peer.ignoreWindowUpdates();
        peer.autoGrant();
        RegisteredTunnel tunnel = harness.establish(peer, "loops");
        SessionContext session = harness.onlySession();
        Visitor visitor = harness.visitorOnAnotherLoop(tunnel.publicHost(), session);
        Channel edge = harness.edgeConnection(visitor);

        visitor.request("POST", "/echo", new byte[200_000]);
        int id = peer.expect(Frame.Open.class, TIMEOUT).streamId();
        long received = 0;
        for (Frame frame = peer.expect(Frame.class, TIMEOUT);
                !(frame instanceof Frame.Close);
                frame = peer.expect(Frame.class, TIMEOUT)) {
            received += ((Frame.Data) frame).payload().readableBytes();
            frame.release();
        }
        peer.send(new Frame.Headers(id, new HttpResponseHead(200, HeaderFields.of(), BodyFraming.CHUNKED)));
        for (int i = 0; i < 8; i++) {
            peer.sendData(id, Unpooled.wrappedBuffer(new byte[50_000]), TIMEOUT);
        }
        peer.send(Frame.Close.half(id));

        assertThat(edge.eventLoop()).isNotSameAs(session.channel().eventLoop());
        assertThat(received).isEqualTo(200_000);
        assertThat(visitor.response().body()).hasSize(400_000);
        assertThat(harness.metrics.events()).noneMatch(event -> event.startsWith("protocolError"));
    }

    @ParameterizedTest
    @MethodSource(TRANSPORTS)
    void eachHalfRefusesACallFromAnotherThread(EdgeTransport transport) throws Exception {
        harness = new HttpHarness(HttpHarness.Settings.defaults(transport));
        ScriptedPeer peer = harness.connect();
        RegisteredTunnel tunnel = harness.establish(peer, "loops");
        SessionContext session = harness.onlySession();
        SessionExchanges exchanges = SessionExchanges.of(session).orElseThrow();
        Visitor visitor = harness.visitor(tunnel.publicHost());
        visitor.request("GET", "/");
        peer.expect(Frame.Open.class, TIMEOUT);
        ExchangeHandler edge = harness.edgeConnection(visitor).pipeline().get(ExchangeHandler.class);

        assertThatIllegalStateException().isThrownBy(exchanges::onReadComplete);
        assertThatIllegalStateException().isThrownBy(exchanges::cancelAll);
        assertThatIllegalStateException().isThrownBy(() -> exchanges.onWritten(1, 1));
        assertThatIllegalStateException().isThrownBy(() -> edge.flush(null));
        assertThatIllegalStateException().isThrownBy(() -> edge.abort(null));
    }
}
