package io.jburrow.protocol.adapter.netty;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToMessageEncoder;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import java.util.List;
import java.util.Objects;

/** Encodes each outbound {@link Frame} as one binary WebSocket message. */
public final class WebSocketFrameEncoder extends MessageToMessageEncoder<Frame> {

    private final FrameCodec codec;

    public WebSocketFrameEncoder(FrameCodec codec) {
        super(Frame.class);
        this.codec = Objects.requireNonNull(codec, "codec");
    }

    /**
     * The codec takes over the frame's buffer, and the superclass releases the frame once this returns, so the frame
     * is retained first: one reference goes into the message, the caller's is released.
     */
    @Override
    protected void encode(ChannelHandlerContext ctx, Frame frame, List<Object> out) {
        out.add(new BinaryWebSocketFrame(codec.encode(frame.retain(), ctx.alloc())));
    }
}
