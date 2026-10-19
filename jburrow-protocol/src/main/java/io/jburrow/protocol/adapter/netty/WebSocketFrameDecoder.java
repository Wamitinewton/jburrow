package io.jburrow.protocol.adapter.netty;

import io.jburrow.protocol.wire.ErrorCode;
import io.jburrow.protocol.wire.ProtocolException;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.DecoderException;
import io.netty.handler.codec.MessageToMessageDecoder;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty.handler.codec.http.websocketx.PingWebSocketFrame;
import io.netty.handler.codec.http.websocketx.PongWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;
import java.util.List;
import java.util.Objects;

/**
 * Decodes each binary WebSocket message into a {@link Frame}. Sits after Netty's WebSocket protocol handler and frame
 * aggregator, so every message it sees is whole.
 *
 * <p>A text message, or a fragment the aggregator didn't merge, is {@code PROTOCOL_ERROR}. WebSocket control frames
 * pass through untouched for Netty's protocol handler to own.
 *
 * <p>A {@link ProtocolException} reaches the next handlers' {@code exceptionCaught} as itself, not wrapped. The
 * session handler decides what to do with it; this handler never closes the channel.
 */
public final class WebSocketFrameDecoder extends MessageToMessageDecoder<WebSocketFrame> {

    private final FrameCodec codec;

    public WebSocketFrameDecoder(FrameCodec codec) {
        super(WebSocketFrame.class);
        this.codec = Objects.requireNonNull(codec, "codec");
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        try {
            super.channelRead(ctx, msg);
        } catch (DecoderException e) {
            if (e.getCause() instanceof ProtocolException protocolError) {
                throw protocolError;
            }
            throw e;
        }
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, WebSocketFrame message, List<Object> out) {
        if (message instanceof PingWebSocketFrame
                || message instanceof PongWebSocketFrame
                || message instanceof CloseWebSocketFrame) {
            out.add(message.retain());
        } else if (message instanceof BinaryWebSocketFrame && message.isFinalFragment()) {
            out.add(codec.decode(message.content()));
        } else {
            throw ProtocolException.session(
                    ErrorCode.PROTOCOL_ERROR, "every protocol frame must be one whole binary WebSocket message");
        }
    }
}
