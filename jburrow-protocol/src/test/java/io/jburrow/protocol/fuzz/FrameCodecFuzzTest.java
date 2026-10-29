package io.jburrow.protocol.fuzz;

import static org.assertj.core.api.Assertions.assertThat;

import com.code_intelligence.jazzer.junit.FuzzTest;
import io.jburrow.protocol.adapter.jackson.ControlJson;
import io.jburrow.protocol.adapter.netty.Frame;
import io.jburrow.protocol.adapter.netty.FrameCodec;
import io.jburrow.protocol.adapter.netty.FrameCodec.Role;
import io.jburrow.protocol.wire.ErrorCode;
import io.jburrow.protocol.wire.ErrorScope;
import io.jburrow.protocol.wire.FrameLimits;
import io.jburrow.protocol.wire.ProtocolException;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import java.nio.ByteBuffer;
import java.util.Set;

/**
 * Feeds arbitrary bytes to {@link FrameCodec#decode} as one WebSocket message, once for each receiving role, in a
 * session without capabilities and in one with {@code http}.
 *
 * <p>The property: decoding returns a frame or throws a session-scoped {@link ProtocolException} with a code the codec
 * may raise, and nothing else. It never changes the input's reference count, a returned {@code DATA} frame's payload
 * is freed once both it and the input are released, and every returned frame the protocol lets the peer encode
 * re-encodes and decodes to an equal frame.
 *
 * <p>Each input is decoded twice: as it is, and with its length field set to agree with the message. Almost every
 * mutation of a message breaks receiver rule 3 (the length field equals the message length minus 10), so without the
 * second form the fuzzer would rarely get past the header to rules 4 to 8 and the payload parsers. The first form keeps
 * rule 3 itself fuzzed.
 *
 * <p>Only {@link ProtocolException} is caught. An {@link OutOfMemoryError} or {@link StackOverflowError} is exactly what
 * the limits exist to prevent, so it propagates and Jazzer reports it.
 */
class FrameCodecFuzzTest {

    private static final Set<ErrorCode> CODEC_ERRORS =
            Set.of(ErrorCode.FRAME_SIZE_ERROR, ErrorCode.PROTOCOL_ERROR, ErrorCode.VALIDATION_FAILED);

    private static final ControlJson JSON = ControlJson.create();
    private static final FrameCodec SERVER = new FrameCodec(Role.SERVER, JSON);
    private static final FrameCodec CLIENT = new FrameCodec(Role.CLIENT, JSON);
    private static final FrameCodec SERVER_WITH_HTTP = new FrameCodec(Role.SERVER, JSON, () -> true);
    private static final FrameCodec CLIENT_WITH_HTTP = new FrameCodec(Role.CLIENT, JSON, () -> true);
    private static final ByteBufAllocator ALLOC = ByteBufAllocator.DEFAULT;
    private static final int LENGTH_FIELD_OFFSET = 6;

    @FuzzTest(maxDuration = "2m")
    void decodeAsServer(byte[] message) {
        decodeBothForms(SERVER, CLIENT, message);
        decodeBothForms(SERVER_WITH_HTTP, CLIENT_WITH_HTTP, message);
    }

    @FuzzTest(maxDuration = "2m")
    void decodeAsClient(byte[] message) {
        decodeBothForms(CLIENT, SERVER, message);
        decodeBothForms(CLIENT_WITH_HTTP, SERVER_WITH_HTTP, message);
    }

    private static void decodeBothForms(FrameCodec receiver, FrameCodec peer, byte[] message) {
        decodeAndCheck(receiver, peer, message);
        if (message.length >= FrameLimits.HEADER_LENGTH) {
            ByteBuffer consistent = ByteBuffer.wrap(message.clone());
            int payloadLength = message.length - FrameLimits.HEADER_LENGTH;
            if (consistent.getInt(LENGTH_FIELD_OFFSET) != payloadLength) {
                decodeAndCheck(
                        receiver,
                        peer,
                        consistent.putInt(LENGTH_FIELD_OFFSET, payloadLength).array());
            }
        }
    }

    /**
     * The input is freed whatever happens, so a finding is reported once, as itself, rather than again as a leak by
     * the leak detection that checks every test class.
     */
    private static void decodeAndCheck(FrameCodec receiver, FrameCodec peer, byte[] bytes) {
        ByteBuf message = ALLOC.buffer(bytes.length).writeBytes(bytes);
        try {
            Frame frame = decodeOrNull(receiver, message);
            if (frame != null) {
                try {
                    assertReencodes(frame, receiver, peer);
                } finally {
                    frame.release();
                }
            }

            assertThat(message.refCnt()).as("input reference count").isEqualTo(1);
            assertThat(message.readableBytes()).as("input readable bytes").isEqualTo(bytes.length);
            message.release();
            if (frame instanceof Frame.Data data) {
                assertThat(data.refCnt()).as("DATA payload reference count").isZero();
            }
        } finally {
            if (message.refCnt() > 0) {
                message.release(message.refCnt());
            }
        }
    }

    private static Frame decodeOrNull(FrameCodec receiver, ByteBuf message) {
        try {
            return receiver.decode(message);
        } catch (ProtocolException rejected) {
            assertThat(rejected.scope()).isEqualTo(ErrorScope.SESSION);
            assertThat(rejected.code()).isIn(CODEC_ERRORS);
            return null;
        }
    }

    /** {@code GOAWAY} is decoded only as far as its header and is never encoded, so it has nothing to round-trip. */
    private static void assertReencodes(Frame frame, FrameCodec receiver, FrameCodec peer) {
        if (frame instanceof Frame.Unsupported) {
            return;
        }
        ByteBuf encoded = peer.encode(frame.retain(), ALLOC);
        try {
            Frame again = receiver.decode(encoded);
            try {
                assertThat(again).isEqualTo(frame);
            } finally {
                again.release();
            }
        } finally {
            encoded.release();
        }
    }
}
