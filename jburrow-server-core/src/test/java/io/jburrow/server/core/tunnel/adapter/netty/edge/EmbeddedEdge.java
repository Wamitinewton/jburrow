package io.jburrow.server.core.tunnel.adapter.netty.edge;

import io.jburrow.protocol.adapter.jackson.ControlJson;
import io.jburrow.protocol.control.Windows;
import io.jburrow.server.core.tunnel.adapter.netty.http.EdgeErrorPage;
import io.jburrow.server.core.tunnel.adapter.netty.http.ExchangeEnded;
import io.jburrow.server.core.tunnel.adapter.netty.http.ExchangeRequest;
import io.jburrow.server.core.tunnel.application.RecordingTunnelMetrics;
import io.jburrow.server.core.tunnel.application.SessionAdmission;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.handler.codec.http.HttpContent;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.concurrent.GlobalEventExecutor;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * One edge connection on an {@link PeerChannel}, built by the real {@link EdgeChannelInitializer}, with a recording
 * exchange handler in place of the real one and a session initializer that records upgrades.
 */
final class EmbeddedEdge {

    static final String CONNECT_HOST = "connect.example.dev";
    static final String DOMAIN = "example.dev";
    static final int MAX_REQUEST_BODY = 1_024;
    static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);
    static final Duration KEEP_ALIVE_TIMEOUT = Duration.ofSeconds(75);

    final SessionAdmission admission = new SessionAdmission(8, 4, 3);
    final List<String> upgrades = new ArrayList<>();
    final RecordingExchanges exchanges = new RecordingExchanges();
    final RecordingTunnelMetrics metrics = new RecordingTunnelMetrics();
    final PeerChannel channel;

    EmbeddedEdge() {
        EdgeSettings settings = settings();
        ConnectRequestFilter filter = new ConnectRequestFilter(
                CONNECT_HOST,
                admission,
                () -> false,
                metrics,
                new UpgradeErrorResponses(ControlJson.create(), InstantSource.system()));
        ClientAddressResolver clientAddresses = new ClientAddressResolver(List.of());
        ConnectPipeline connect = new ConnectPipeline(
                settings,
                clientAddresses,
                filter,
                (upgraded, subprotocol, clientAddress, ticket) -> upgrades.add(subprotocol));
        channel = new PeerChannel(
                new InetSocketAddress("192.0.2.10", 40_000),
                new EdgeChannelInitializer(
                        settings,
                        new DefaultChannelGroup(GlobalEventExecutor.INSTANCE),
                        connect,
                        clientAddresses,
                        () -> exchanges,
                        new EdgeErrorPage(),
                        metrics));
    }

    static EdgeSettings settings() {
        return new EdgeSettings(
                new InetSocketAddress("127.0.0.1", 0),
                CONNECT_HOST,
                EdgeTransport.NIO,
                1,
                List.of(),
                8,
                4,
                REQUEST_TIMEOUT,
                Duration.ofSeconds(10),
                Duration.ofSeconds(15),
                Duration.ofSeconds(45),
                128 * 1024,
                512 * 1024,
                new Windows(256 * 1024, 512 * 1024),
                1,
                8,
                "test",
                new HttpSettings(
                        DOMAIN,
                        MAX_REQUEST_BODY,
                        Duration.ofSeconds(60),
                        KEEP_ALIVE_TIMEOUT,
                        16 * 1024,
                        Duration.ofSeconds(1),
                        16L * 1024 * 1024,
                        2L * 1024 * 1024),
                Set.of());
    }

    void send(String bytes) {
        channel.writeInbound(Unpooled.copiedBuffer(bytes, StandardCharsets.ISO_8859_1));
    }

    /**
     * The bytes written to the connection since the last call. Netty's WebSocket handler also writes a close frame
     * object when a connection in connect mode closes, which a real socket refuses; it is dropped here.
     */
    String written() {
        StringBuilder out = new StringBuilder();
        Object msg;
        while ((msg = channel.readOutbound()) != null) {
            if (msg instanceof ByteBuf buf) {
                out.append(buf.toString(StandardCharsets.UTF_8));
            }
            ReferenceCountUtil.release(msg);
        }
        return out.toString();
    }

    static String get(String host, String path) {
        return "GET " + path + " HTTP/1.1\r\nHost: " + host + "\r\n\r\n";
    }

    static String upgrade(String host) {
        return "GET /v1/connect HTTP/1.1\r\n"
                + "Host: " + host + "\r\n"
                + "Upgrade: websocket\r\n"
                + "Connection: Upgrade\r\n"
                + "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n"
                + "Sec-WebSocket-Version: 13\r\n"
                + "Sec-WebSocket-Protocol: jburrow.v1-draft.2\r\n"
                + "\r\n";
    }

    void close() {
        channel.finishAndReleaseAll();
    }

    /** Stands in for the exchange handler: records what it is given, and ends an exchange when told to. */
    static final class RecordingExchanges extends ChannelInboundHandlerAdapter {

        final List<ExchangeRequest> requests = new ArrayList<>();
        final List<Integer> contentBytes = new ArrayList<>();
        private ChannelHandlerContext ctx;

        @Override
        public boolean isSharable() {
            return true;
        }

        @Override
        public void handlerAdded(ChannelHandlerContext ctx) {
            this.ctx = ctx;
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            if (msg instanceof ExchangeRequest request) {
                requests.add(request);
            } else if (msg instanceof HttpContent content) {
                contentBytes.add(content.content().readableBytes());
            }
            ReferenceCountUtil.release(msg);
        }

        void end() {
            ctx.pipeline().fireUserEventTriggered(ExchangeEnded.INSTANCE);
        }

        boolean added() {
            return ctx != null && !ctx.isRemoved();
        }
    }
}
