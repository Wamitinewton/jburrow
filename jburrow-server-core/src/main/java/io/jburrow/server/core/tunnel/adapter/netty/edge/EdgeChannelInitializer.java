package io.jburrow.server.core.tunnel.adapter.netty.edge;

import io.jburrow.protocol.wire.FrameLimits;
import io.jburrow.protocol.wire.ProtocolVersion;
import io.jburrow.server.core.tunnel.application.HandshakeTicket;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.http.HttpDecoderConfig;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.websocketx.WebSocketFrameAggregator;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolConfig;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler.HandshakeComplete;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler.ServerHandshakeStateEvent;
import io.netty.util.concurrent.ScheduledFuture;
import java.net.InetAddress;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Builds the pipeline of an accepted edge connection, in the order of
 * {@code docs/phase-1-tcp-tunnel/ARCHITECTURE.md} §The edge listener, up to the WebSocket upgrade. When the upgrade
 * completes it removes the request-phase handlers, adds the message aggregator and hands the channel to the
 * {@link SessionInitializer}.
 */
final class EdgeChannelInitializer extends ChannelInitializer<SocketChannel> {

    static final String REQUEST_DEADLINE = "request-deadline";
    static final String HTTP_CODEC = "http-codec";
    static final String HTTP_AGGREGATOR = "http-aggregator";
    static final String CLIENT_ADDRESS = "client-address";
    static final String CONNECT_FILTER = "connect-filter";
    static final String WEBSOCKET = "websocket";
    static final String UPGRADE = "upgrade";
    static final String WEBSOCKET_AGGREGATOR = "websocket-aggregator";

    static final int MAX_INITIAL_LINE_LENGTH = 4_096;
    static final int MAX_HEADER_SIZE = 8_192;
    static final int MAX_REQUEST_BODY = 1_024;
    static final long FORCE_CLOSE_TIMEOUT_MILLIS = 2_000;

    private static final Logger log = LoggerFactory.getLogger(EdgeChannelInitializer.class);

    private final long requestTimeoutNanos;
    private final ChannelGroup connections;
    private final ClientAddressResolver clientAddresses;
    private final ConnectRequestFilter filter;
    private final WebSocketServerProtocolConfig webSocket;
    private final SessionInitializer sessions;

    EdgeChannelInitializer(
            EdgeSettings settings,
            ChannelGroup connections,
            ClientAddressResolver clientAddresses,
            ConnectRequestFilter filter,
            SessionInitializer sessions) {
        this.requestTimeoutNanos = settings.requestTimeout().toNanos();
        this.connections = Objects.requireNonNull(connections, "connections");
        this.clientAddresses = Objects.requireNonNull(clientAddresses, "clientAddresses");
        this.filter = Objects.requireNonNull(filter, "filter");
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.webSocket = webSocketConfig(settings);
    }

    /**
     * {@code checkStartsWith} is on so a query string doesn't make Netty pass the request by as not its own;
     * {@link ConnectRequestFilter} has already required the path to be exactly {@code /v1/connect}. Close frames are
     * left to the session, so it knows the client closed.
     */
    private static WebSocketServerProtocolConfig webSocketConfig(EdgeSettings settings) {
        return WebSocketServerProtocolConfig.newBuilder()
                .websocketPath(ConnectRequestFilter.CONNECT_PATH)
                .checkStartsWith(true)
                .subprotocols(String.join(",", ProtocolVersion.supported()))
                .allowExtensions(false)
                .expectMaskedFrames(true)
                .allowMaskMismatch(false)
                .maxFramePayloadLength(FrameLimits.MAX_MESSAGE_LENGTH)
                .handshakeTimeoutMillis(settings.handshakeTimeout().toMillis())
                .forceCloseTimeoutMillis(FORCE_CLOSE_TIMEOUT_MILLIS)
                .closeOnProtocolViolation(true)
                .dropPongFrames(true)
                .handleCloseFrames(false)
                .build();
    }

    @Override
    protected void initChannel(SocketChannel channel) {
        connections.add(channel);
        HttpDecoderConfig http = new HttpDecoderConfig()
                .setMaxInitialLineLength(MAX_INITIAL_LINE_LENGTH)
                .setMaxHeaderSize(MAX_HEADER_SIZE);
        channel.pipeline()
                .addLast(REQUEST_DEADLINE, new RequestDeadline(requestTimeoutNanos))
                .addLast(HTTP_CODEC, new HttpServerCodec(http))
                .addLast(HTTP_AGGREGATOR, new HttpObjectAggregator(MAX_REQUEST_BODY))
                .addLast(CLIENT_ADDRESS, clientAddresses)
                .addLast(CONNECT_FILTER, filter)
                .addLast(WEBSOCKET, new WebSocketServerProtocolHandler(webSocket))
                .addLast(UPGRADE, new UpgradeCompletion(sessions));
    }

    /**
     * Closes the connection if its upgrade hasn't completed in time. A deadline for the whole request rather than a
     * read timeout, which a client sending one byte at a time would reset for ever.
     */
    private static final class RequestDeadline extends ChannelInboundHandlerAdapter {

        private final long timeoutNanos;
        private ScheduledFuture<?> deadline;

        RequestDeadline(long timeoutNanos) {
            this.timeoutNanos = timeoutNanos;
        }

        @Override
        public void handlerAdded(ChannelHandlerContext ctx) {
            if (ctx.channel().isActive()) {
                arm(ctx);
            }
        }

        @Override
        public void channelActive(ChannelHandlerContext ctx) {
            arm(ctx);
            ctx.fireChannelActive();
        }

        @Override
        public void handlerRemoved(ChannelHandlerContext ctx) {
            if (deadline != null) {
                deadline.cancel(false);
            }
        }

        private void arm(ChannelHandlerContext ctx) {
            if (deadline == null) {
                deadline = ctx.executor()
                        .schedule(
                                () -> {
                                    log.debug(
                                            "Upgrade request timed out: client {}",
                                            ClientAddressResolver.clientAddress(ctx.channel())
                                                    .getHostAddress());
                                    ctx.close();
                                },
                                timeoutNanos,
                                TimeUnit.NANOSECONDS);
            }
        }
    }

    /** Waits for Netty's handshake events and turns the upgraded channel over to the session. */
    private static final class UpgradeCompletion extends ChannelInboundHandlerAdapter {

        private final SessionInitializer sessions;

        UpgradeCompletion(SessionInitializer sessions) {
            this.sessions = sessions;
        }

        @Override
        public void userEventTriggered(ChannelHandlerContext ctx, Object event) throws Exception {
            if (event instanceof HandshakeComplete complete) {
                upgraded(ctx, complete.selectedSubprotocol());
            } else if (event == ServerHandshakeStateEvent.HANDSHAKE_TIMEOUT) {
                log.debug(
                        "WebSocket handshake timed out: client {}",
                        ClientAddressResolver.clientAddress(ctx.channel()).getHostAddress());
                ctx.close();
            } else {
                super.userEventTriggered(ctx, event);
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            log.debug(
                    "Edge connection failed before its session began: client {}",
                    ClientAddressResolver.clientAddress(ctx.channel()).getHostAddress(),
                    cause);
            ctx.close();
        }

        private void upgraded(ChannelHandlerContext ctx, String subprotocol) {
            HandshakeTicket ticket = ConnectRequestFilter.ticket(ctx.channel());
            if (subprotocol == null || ticket == null) {
                throw new IllegalStateException("An upgrade completed without passing the connect filter");
            }
            InetAddress clientAddress = ClientAddressResolver.clientAddress(ctx.channel());
            ChannelPipeline pipeline = ctx.pipeline();
            pipeline.remove(REQUEST_DEADLINE);
            pipeline.remove(CLIENT_ADDRESS);
            pipeline.remove(CONNECT_FILTER);
            pipeline.replace(this, WEBSOCKET_AGGREGATOR, new WebSocketFrameAggregator(FrameLimits.MAX_MESSAGE_LENGTH));
            try {
                sessions.initialize(ctx.channel(), subprotocol, clientAddress, ticket);
            } catch (RuntimeException e) {
                log.error("Could not start a session: client {}", clientAddress.getHostAddress(), e);
                ctx.channel().close();
            }
        }
    }
}
