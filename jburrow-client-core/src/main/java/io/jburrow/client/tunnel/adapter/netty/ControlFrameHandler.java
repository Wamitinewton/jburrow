package io.jburrow.client.tunnel.adapter.netty;

import io.jburrow.client.TunnelStartException;
import io.jburrow.client.tunnel.domain.ClientSession;
import io.jburrow.protocol.adapter.netty.Frame;
import io.jburrow.protocol.wire.ErrorCode;
import io.jburrow.protocol.wire.FrameLimits;
import io.jburrow.protocol.wire.ProtocolException;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.TooLongFrameException;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty.handler.codec.http.websocketx.CorruptedWebSocketFrameException;
import io.netty.handler.codec.http.websocketx.WebSocketClientProtocolHandler;
import io.netty.handler.codec.http.websocketx.WebSocketClientProtocolHandler.ClientHandshakeStateEvent;
import io.netty.handler.codec.http.websocketx.WebSocketCloseStatus;
import io.netty.handler.ssl.SslHandshakeCompletionEvent;
import io.netty.util.ReferenceCountUtil;
import java.io.IOException;
import java.util.Objects;

/**
 * The last handler of the control connection: turns its events into calls on the {@link ControlConnection},
 * {@link ClientSession} and {@link LocalConnector}, which make every decision. Every frame from the server is checked
 * against the session's state before it is acted on (§8.2), so a frame out of order is {@code PROTOCOL_ERROR} however
 * well-formed it is.
 *
 * <p>Releases frames itself: a {@code DATA} frame's buffer is handed on to its stream rather than released here.
 */
final class ControlFrameHandler extends ChannelInboundHandlerAdapter {

    private final ControlConnection connection;
    private final ClientSession session;
    private final ClientLivenessHandler liveness;
    private final LocalConnector streams;

    ControlFrameHandler(ControlConnection connection, ClientLivenessHandler liveness, LocalConnector streams) {
        this.connection = Objects.requireNonNull(connection, "connection");
        this.session = connection.session();
        this.liveness = Objects.requireNonNull(liveness, "liveness");
        this.streams = Objects.requireNonNull(streams, "streams");
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        connection.onConnected(ctx.channel(), streams);
        ctx.fireChannelActive();
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object event) {
        if (event == ClientHandshakeStateEvent.HANDSHAKE_COMPLETE) {
            WebSocketClientProtocolHandler websocket = ctx.pipeline().get(WebSocketClientProtocolHandler.class);
            connection.onUpgraded(websocket.handshaker().actualSubprotocol());
        } else if (event == ClientHandshakeStateEvent.HANDSHAKE_TIMEOUT) {
            connection.startFailed(new TunnelStartException(
                    TunnelStartException.Kind.TIMEOUT, "The server didn't complete the WebSocket upgrade in time."));
        } else if (event instanceof SslHandshakeCompletionEvent tls && !tls.isSuccess()) {
            connection.startFailed(UpgradeFailures.fromCause(tls.cause()));
        }
        ReferenceCountUtil.release(event);
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        switch (msg) {
            case Frame frame -> onFrame(frame);
            case CloseWebSocketFrame close -> {
                int status = close.statusCode();
                close.release();
                connection.peerClosed(status);
            }
            default -> ReferenceCountUtil.release(msg);
        }
    }

    private void onFrame(Frame frame) {
        boolean handedOn = false;
        try {
            if (session.isClosing()) {
                return;
            }
            session.checkInbound(frame.type());
            switch (frame) {
                case Frame.ServerHello hello -> {
                    liveness.start(session.onServerHello(hello.message()));
                    connection.onServerHello();
                }
                case Frame.Registered registered ->
                    connection.onEstablished(session.onRegistered(registered.message()));
                case Frame.Open open -> streams.open(open);
                case Frame.Data data -> {
                    handedOn = true;
                    streams.onData(data);
                }
                case Frame.WindowUpdate update -> streams.onWindowUpdate(update);
                case Frame.Close close -> streams.onClose(close);
                case Frame.Error error -> connection.peerFailed(error.body());
                case Frame.Ping ping -> {}
                case Frame.Pong pong -> {}
                default -> throw new IllegalStateException(frame.type() + " passed the session's frame order");
            }
        } catch (ProtocolException e) {
            connection.fail(e);
        } catch (RuntimeException e) {
            connection.failInternally(e);
        } finally {
            if (!handedOn) {
                frame.release();
            }
        }
    }

    @Override
    public void channelReadComplete(ChannelHandlerContext ctx) {
        streams.onReadComplete();
    }

    @Override
    public void channelWritabilityChanged(ChannelHandlerContext ctx) {
        streams.onControlWritabilityChanged(ctx.channel().isWritable());
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        if (!connection.isUpgraded()) {
            connection.startFailed(UpgradeFailures.fromCause(cause));
            return;
        }
        switch (cause) {
            case ProtocolException error -> connection.fail(error);
            case CorruptedWebSocketFrameException violation ->
                connection.abort(
                        violation.closeStatus() == WebSocketCloseStatus.MESSAGE_TOO_BIG
                                ? ErrorCode.FRAME_SIZE_ERROR
                                : ErrorCode.PROTOCOL_ERROR,
                        "The server broke the WebSocket protocol: " + violation.getMessage());
            case TooLongFrameException tooLong ->
                connection.fail(ProtocolException.session(
                        ErrorCode.FRAME_SIZE_ERROR,
                        "A message is longer than " + FrameLimits.MAX_MESSAGE_LENGTH + " bytes."));
            case IOException lost -> connection.connectionLost("The connection to the server failed: " + lost);
            default -> connection.failInternally(cause);
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        connection.tearDown();
        ctx.fireChannelInactive();
    }
}
