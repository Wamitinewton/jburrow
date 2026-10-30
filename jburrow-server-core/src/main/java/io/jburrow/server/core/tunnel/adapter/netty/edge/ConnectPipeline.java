package io.jburrow.server.core.tunnel.adapter.netty.edge;

import io.jburrow.protocol.wire.FrameLimits;
import io.jburrow.protocol.wire.ProtocolVersion;
import io.jburrow.server.core.tunnel.adapter.netty.http.DemandReadAllocator;
import io.jburrow.server.core.tunnel.application.HandshakeTicket;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.websocketx.WebSocketFrameAggregator;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolConfig;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler.HandshakeComplete;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler.ServerHandshakeStateEvent;
import java.net.InetAddress;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Phase 1's connect handlers, in the order of {@code docs/phase-1-tcp-tunnel/ARCHITECTURE.md} §The edge listener,
 * installed behind the {@link HostDispatcher} for a request to the connect host. A connect request ends the request
 * phase of its connection either way: the upgrade completes, or {@link ConnectRequestFilter} refuses it and closes
 * the connection as {@code docs/protocol.md} §3.5 requires.
 *
 * <p>When the upgrade completes, the request-phase handlers, the dispatcher included, are removed, a message
 * aggregator is added, the channel's reads are sized as Netty sizes them, and the channel is handed to the
 * {@link SessionInitializer}.
 */
final class ConnectPipeline {

    static final String HTTP_AGGREGATOR = "http-aggregator";
    static final String CLIENT_ADDRESS = "client-address";
    static final String CONNECT_FILTER = "connect-filter";
    static final String WEBSOCKET = "websocket";
    static final String UPGRADE = "upgrade";
    static final String WEBSOCKET_AGGREGATOR = "websocket-aggregator";

    static final int MAX_REQUEST_BODY = 1_024;
    static final long FORCE_CLOSE_TIMEOUT_MILLIS = 2_000;

    private static final Logger log = LoggerFactory.getLogger(ConnectPipeline.class);

    private final ClientAddressResolver clientAddresses;
    private final ConnectRequestFilter filter;
    private final WebSocketServerProtocolConfig webSocket;
    private final SessionInitializer sessions;

    ConnectPipeline(
            EdgeSettings settings,
            ClientAddressResolver clientAddresses,
            ConnectRequestFilter filter,
            SessionInitializer sessions) {
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

    /** Adds the connect handlers directly behind the handler named {@code after}. */
    void install(ChannelPipeline pipeline, String after) {
        pipeline.addAfter(after, HTTP_AGGREGATOR, new HttpObjectAggregator(MAX_REQUEST_BODY))
                .addAfter(HTTP_AGGREGATOR, CLIENT_ADDRESS, clientAddresses)
                .addAfter(CLIENT_ADDRESS, CONNECT_FILTER, filter)
                .addAfter(CONNECT_FILTER, WEBSOCKET, new WebSocketServerProtocolHandler(webSocket))
                .addAfter(WEBSOCKET, UPGRADE, new UpgradeCompletion(sessions));
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
            pipeline.remove(EdgeChannelInitializer.REQUEST_DEADLINE);
            pipeline.remove(EdgeChannelInitializer.HOST_DISPATCHER);
            pipeline.remove(CLIENT_ADDRESS);
            pipeline.remove(CONNECT_FILTER);
            pipeline.replace(this, WEBSOCKET_AGGREGATOR, new WebSocketFrameAggregator(FrameLimits.MAX_MESSAGE_LENGTH));
            DemandReadAllocator.toSessionReads(ctx.channel());
            try {
                sessions.initialize(ctx.channel(), subprotocol, clientAddress, ticket);
            } catch (RuntimeException e) {
                log.error("Could not start a session: client {}", clientAddress.getHostAddress(), e);
                ctx.channel().close();
            }
        }
    }
}
