package io.jburrow.protocol.adapter.netty;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static org.assertj.core.api.Assertions.assertThat;

import io.jburrow.protocol.adapter.jackson.ControlJson;
import io.jburrow.protocol.testsupport.Hex;
import io.jburrow.protocol.wire.ErrorCode;
import io.jburrow.protocol.wire.ProtocolException;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty.handler.codec.http.websocketx.ContinuationWebSocketFrame;
import io.netty.handler.codec.http.websocketx.PingWebSocketFrame;
import io.netty.handler.codec.http.websocketx.PongWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketCloseStatus;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class WebSocketBridgeTest {

    private static final FrameCodec CODEC = new FrameCodec(FrameCodec.Role.SERVER, ControlJson.create());

    private final List<Throwable> caught = new ArrayList<>();
    private EmbeddedChannel channel;

    @BeforeEach
    void setUp() {
        channel = new EmbeddedChannel(
                new WebSocketFrameDecoder(CODEC), new WebSocketFrameEncoder(CODEC), new ExceptionRecorder());
    }

    @AfterEach
    void tearDown() {
        channel.finishAndReleaseAll();
    }

    @Test
    void decodesABinaryMessageIntoAFrameAndReleasesTheMessage() {
        BinaryWebSocketFrame message = binary("00000000 08 00 00000008 0000000000000007");

        channel.writeInbound(message);

        assertThat((Object) channel.readInbound()).isEqualTo(new Frame.Ping(7));
        assertThat(message.refCnt()).isZero();
    }

    @Test
    void decodedDataOutlivesTheMessage() {
        BinaryWebSocketFrame message = binary("00000001 05 00 00000002 6869");

        channel.writeInbound(message);
        Frame.Data data = channel.readInbound();

        assertThat(message.refCnt()).isEqualTo(1);
        assertThat(data.payload().toString(US_ASCII)).isEqualTo("hi");
        data.release();
        assertThat(message.refCnt()).isZero();
    }

    @Test
    void aTextMessageIsAProtocolErrorDeliveredUnwrapped() {
        TextWebSocketFrame text = new TextWebSocketFrame("{}");

        channel.writeInbound(text);

        assertProtocolError(ErrorCode.PROTOCOL_ERROR);
        assertThat(text.refCnt()).isZero();
        assertThat((Object) channel.readInbound()).isNull();
    }

    @Test
    void aFragmentTheAggregatorDidNotMergeIsAProtocolError() {
        BinaryWebSocketFrame first = new BinaryWebSocketFrame(false, 0, ascii("00"));
        ContinuationWebSocketFrame rest = new ContinuationWebSocketFrame(true, 0, ascii("00"));

        channel.writeInbound(first);
        channel.writeInbound(rest);

        assertThat(caught)
                .hasSize(2)
                .allSatisfy(error -> assertThat(error)
                        .isInstanceOfSatisfying(
                                ProtocolException.class,
                                e -> assertThat(e.code()).isEqualTo(ErrorCode.PROTOCOL_ERROR)));
        assertThat(first.refCnt()).isZero();
        assertThat(rest.refCnt()).isZero();
    }

    @Test
    void aMalformedFrameIsTheCodecsErrorDeliveredUnwrapped() {
        BinaryWebSocketFrame message = binary("00000001 05 00");

        channel.writeInbound(message);

        assertProtocolError(ErrorCode.FRAME_SIZE_ERROR);
        assertThat(message.refCnt()).isZero();
    }

    @Test
    void theBridgeNeverClosesTheChannelItself() {
        channel.writeInbound(new TextWebSocketFrame("x"));

        assertThat(channel.isOpen()).isTrue();
    }

    @Test
    void webSocketControlFramesPassThroughUntouched() {
        List<WebSocketFrame> controls = List.of(
                new PingWebSocketFrame(ascii("p")),
                new PongWebSocketFrame(ascii("q")),
                new CloseWebSocketFrame(WebSocketCloseStatus.NORMAL_CLOSURE));

        for (WebSocketFrame control : controls) {
            channel.writeInbound(control);
            WebSocketFrame passed = channel.readInbound();

            assertThat(passed).isSameAs(control);
            assertThat(passed.refCnt()).isEqualTo(1);
            passed.release();
        }
        assertThat(caught).isEmpty();
    }

    @Test
    void encodesAFrameAsOneBinaryMessage() {
        channel.writeOutbound(new Frame.Pong(7));

        BinaryWebSocketFrame message = channel.readOutbound();
        assertThat(ByteBufUtil.hexDump(message.content()))
                .isEqualTo("00000000" + "09" + "00" + "00000008" + "0000000000000007");
        message.release();
    }

    @Test
    void encodedDataKeepsExactlyOneReferenceToThePayload() {
        ByteBuf payload = ascii("hello");

        channel.writeOutbound(new Frame.Data(1, payload));
        BinaryWebSocketFrame message = channel.readOutbound();

        assertThat(payload.refCnt()).isEqualTo(1);
        assertThat(message.content().toString(10, 5, US_ASCII)).isEqualTo("hello");
        message.release();
        assertThat(payload.refCnt()).isZero();
    }

    @Test
    void aFrameTheCodecRefusesFailsTheWriteAndIsReleased() {
        ByteBuf empty = channel.alloc().buffer(0);

        ChannelFuture write = channel.writeOneOutbound(new Frame.Data(1, empty));
        channel.flushOutbound();

        assertThat(write.isSuccess()).isFalse();
        assertThat(write.cause()).hasRootCauseInstanceOf(IllegalArgumentException.class);
        assertThat(empty.refCnt()).isZero();
        assertThat((Object) channel.readOutbound()).isNull();
    }

    private void assertProtocolError(ErrorCode code) {
        assertThat(caught)
                .singleElement()
                .isInstanceOfSatisfying(
                        ProtocolException.class, e -> assertThat(e.code()).isEqualTo(code));
    }

    private BinaryWebSocketFrame binary(String hex) {
        byte[] bytes = Hex.decode(hex);
        return new BinaryWebSocketFrame(channel.alloc().buffer(bytes.length).writeBytes(bytes));
    }

    private ByteBuf ascii(String text) {
        return channel.alloc().buffer().writeBytes(text.getBytes(US_ASCII));
    }

    /** Stands in for the session handler, which owns the reaction to a protocol error. */
    private final class ExceptionRecorder extends ChannelInboundHandlerAdapter {
        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            caught.add(cause);
        }
    }
}
