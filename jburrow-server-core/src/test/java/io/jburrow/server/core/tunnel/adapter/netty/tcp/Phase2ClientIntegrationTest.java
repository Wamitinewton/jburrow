package io.jburrow.server.core.tunnel.adapter.netty.tcp;

import static io.jburrow.server.core.tunnel.adapter.netty.tcp.TunnelHarness.TIMEOUT;
import static org.assertj.core.api.Assertions.assertThat;

import io.jburrow.protocol.adapter.netty.Frame;
import io.jburrow.protocol.control.ClientInfo;
import io.jburrow.protocol.control.Hello;
import io.jburrow.protocol.control.Register;
import io.jburrow.protocol.control.RegisteredTunnel;
import io.jburrow.protocol.control.TunnelKind;
import io.jburrow.protocol.control.TunnelRequest;
import io.jburrow.protocol.testsupport.ScriptedPeer;
import io.jburrow.protocol.wire.Capability;
import io.jburrow.server.core.portpool.domain.PortRange;
import io.netty.buffer.Unpooled;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** A phase 2 client, which offers no capability, against a server that enables {@code http}: draft 2 unchanged. */
class Phase2ClientIntegrationTest {

    private static final int FIRST_PORT = 41_400;

    private TunnelHarness harness;

    @AfterEach
    void stop() throws Exception {
        if (harness != null) {
            harness.close();
        }
    }

    @Test
    void aClientOfferingNothingGetsNoCapabilityAndATcpTunnelAsBefore() throws Exception {
        harness = new TunnelHarness(
                PortRange.parse(FIRST_PORT + "-" + (FIRST_PORT + 4)),
                new Random(),
                TunnelHarness.Settings.defaults().capabilities(Set.of(Capability.HTTP)));
        ScriptedPeer peer = harness.connect();

        peer.send(new Frame.Hello(new Hello(
                new ClientInfo("jburrow", "0.2.0", "linux", "amd64"), List.of(), ScriptedPeer.CLIENT_WINDOWS)));
        assertThat(peer.expect(Frame.ServerHello.class, TIMEOUT).message().capabilities())
                .isEmpty();
        peer.send(new Frame.Register(
                new Register(TunnelHarness.TOKEN, List.of(new TunnelRequest("default", TunnelKind.TCP, null)))));
        RegisteredTunnel tunnel =
                peer.expect(Frame.Registered.class, TIMEOUT).message().tunnels().getFirst();

        assertThat(peer.httpNegotiated()).isFalse();
        assertThat(tunnel.url()).isEqualTo("tcp://" + TunnelHarness.PUBLIC_HOST + ":" + tunnel.publicPort());
        try (Socket visitor = new Socket(InetAddress.getLoopbackAddress(), tunnel.publicPort())) {
            visitor.setSoTimeout((int) TIMEOUT.toMillis());
            Frame.Open open = peer.expect(Frame.Open.class, TIMEOUT);
            assertThat(open.message().kind()).isEqualTo(TunnelKind.TCP);

            peer.send(new Frame.Data(open.streamId(), Unpooled.copiedBuffer("draft 2", StandardCharsets.US_ASCII)));

            assertThat(visitor.getInputStream().readNBytes(7)).isEqualTo("draft 2".getBytes(StandardCharsets.US_ASCII));
        }
    }
}
