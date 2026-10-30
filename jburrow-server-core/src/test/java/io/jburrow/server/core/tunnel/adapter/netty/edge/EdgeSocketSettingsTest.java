package io.jburrow.server.core.tunnel.adapter.netty.edge;

import static io.jburrow.server.core.tunnel.adapter.netty.edge.RealEdge.SOCKET_BUFFER;
import static io.jburrow.server.core.tunnel.adapter.netty.edge.RealEdge.awaitTrue;
import static io.jburrow.server.core.tunnel.adapter.netty.edge.RealEdge.read;
import static io.jburrow.server.core.tunnel.adapter.netty.edge.RealEdge.write;
import static org.assertj.core.api.Assertions.assertThat;

import io.netty.channel.Channel;
import io.netty.channel.ChannelOption;
import java.io.InputStream;
import java.net.Socket;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class EdgeSocketSettingsTest {

    private RealEdge edge;

    @AfterEach
    void stop() throws Exception {
        if (edge != null) {
            edge.close();
        }
    }

    @ParameterizedTest
    @MethodSource("io.jburrow.server.core.tunnel.adapter.netty.support.Transports#available")
    void aConnectionCarryingExchangesGetsTheHttpSocketBuffersOnce(EdgeTransport transport) throws Exception {
        edge = new RealEdge(transport);

        try (Socket socket = edge.socket()) {
            InputStream in = socket.getInputStream();
            write(socket, edge.get("quiet-otter.localhost"));
            assertThat(read(in).status()).isEqualTo(404);
            Channel channel = edge.onlyConnection();
            int sendBuffer = channel.config().getOption(ChannelOption.SO_SNDBUF);
            int receiveBuffer = channel.config().getOption(ChannelOption.SO_RCVBUF);
            write(socket, edge.get("quiet-otter.localhost"));
            assertThat(read(in).status()).isEqualTo(404);

            assertThat(HostDispatcher.carriesTunnels(channel)).isTrue();
            assertThat(sendBuffer)
                    .as("what was asked for, which Linux doubles")
                    .isBetween(SOCKET_BUFFER, 2 * SOCKET_BUFFER);
            assertThat(receiveBuffer).isBetween(SOCKET_BUFFER, 2 * SOCKET_BUFFER);
            assertThat(channel.config().getOption(ChannelOption.SO_SNDBUF)).isEqualTo(sendBuffer);
            assertThat(channel.config().getOption(ChannelOption.SO_RCVBUF)).isEqualTo(receiveBuffer);
        }
    }

    @ParameterizedTest
    @MethodSource("io.jburrow.server.core.tunnel.adapter.netty.support.Transports#available")
    void aConnectConnectionKeepsTheKernelsBuffers(EdgeTransport transport) throws Exception {
        edge = new RealEdge(transport);

        try (Socket socket = edge.socket()) {
            write(socket, edge.upgradeRequest());
            assertThat(read(socket.getInputStream()).status()).isEqualTo(101);
            awaitTrue(() -> edge.upgraded.size() == 1);

            assertThat(HostDispatcher.carriesTunnels(edge.upgraded.get(0))).isFalse();
        }
    }

    @ParameterizedTest
    @MethodSource("io.jburrow.server.core.tunnel.adapter.netty.support.Transports#available")
    void aConnectionAnsweredOnlyWithPagesKeepsTheKernelsBuffers(EdgeTransport transport) throws Exception {
        edge = new RealEdge(transport);

        try (Socket socket = edge.socket()) {
            write(socket, edge.get("nothing.test"));
            assertThat(read(socket.getInputStream()).status()).isEqualTo(404);

            assertThat(HostDispatcher.carriesTunnels(edge.onlyConnection())).isFalse();
        }
    }
}
