package io.jburrow.protocol.adapter.netty;

import static io.jburrow.protocol.adapter.netty.FrameCodec.Role.CLIENT;
import static io.jburrow.protocol.adapter.netty.FrameCodec.Role.SERVER;
import static java.nio.charset.StandardCharsets.US_ASCII;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import io.jburrow.protocol.adapter.jackson.ControlJson;
import io.jburrow.protocol.adapter.netty.FrameCodec.Role;
import io.jburrow.protocol.control.ClientInfo;
import io.jburrow.protocol.control.ErrorBody;
import io.jburrow.protocol.control.Hello;
import io.jburrow.protocol.control.Open;
import io.jburrow.protocol.control.Register;
import io.jburrow.protocol.control.Registered;
import io.jburrow.protocol.control.RegisteredTunnel;
import io.jburrow.protocol.control.RemoteAddress;
import io.jburrow.protocol.control.ServerHello;
import io.jburrow.protocol.control.ServerInfo;
import io.jburrow.protocol.control.SessionLimits;
import io.jburrow.protocol.control.TunnelKind;
import io.jburrow.protocol.control.TunnelRequest;
import io.jburrow.protocol.control.Windows;
import io.jburrow.protocol.wire.ErrorCode;
import io.jburrow.protocol.wire.ErrorScope;
import io.jburrow.protocol.wire.FrameType;
import io.jburrow.protocol.wire.ProtocolException;
import io.jburrow.protocol.wire.StreamIds;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.CompositeByteBuf;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

class FrameCodecTest {

    private static final ByteBufAllocator ALLOC = ByteBufAllocator.DEFAULT;
    private static final ControlJson JSON = ControlJson.create();
    private static final FrameCodec SERVER_CODEC = new FrameCodec(SERVER, JSON);
    private static final FrameCodec CLIENT_CODEC = new FrameCodec(CLIENT, JSON);

    private static final UUID TUNNEL_ID = UUID.fromString("5d0f4f0e-31a6-4a39-8d51-0c0f7f1f6c23");
    private static final String TOKEN = "jb_LOCAL-DEVELOPMENT-ONLY-unlocks-localhost-00";

    /** One frame of every kind, with the role that sends it. Data frames are built fresh, since encoding takes them. */
    static Stream<Arguments> sentFrames() {
        return Stream.of(
                sent(
                        CLIENT,
                        () -> new Frame.Hello(new Hello(
                                new ClientInfo("jburrow-cli", "0.2.0", null, null),
                                List.of(),
                                new Windows(262_144, 1_048_576)))),
                sent(
                        SERVER,
                        () -> new Frame.ServerHello(new ServerHello(
                                UUID.fromString("0b8e5c1e-7c55-4c46-9a3e-2f1d0c9e4b7a"),
                                new ServerInfo("jburrow-server", "0.2.0"),
                                List.of(),
                                new SessionLimits(65_536, 1, 1, 15_000, 45_000),
                                new Windows(262_144, 524_288)))),
                sent(
                        CLIENT,
                        () -> new Frame.Register(
                                new Register(TOKEN, List.of(new TunnelRequest("default", TunnelKind.TCP, 20_417))))),
                sent(
                        SERVER,
                        () -> new Frame.Registered(new Registered(List.of(RegisteredTunnel.of(
                                "default", TUNNEL_ID, TunnelKind.TCP, "tcp.cloudnativehub.app", 20_417))))),
                sent(SERVER, () -> new Frame.Open(1, new Open(TUNNEL_ID, TunnelKind.TCP, new RemoteAddress("::1", 0)))),
                sent(CLIENT, () -> new Frame.Data(3, ascii("hello"))),
                sent(SERVER, () -> new Frame.Data(StreamIds.MAX, ALLOC.buffer().writeZero(65_536))),
                sent(CLIENT, () -> new Frame.WindowUpdate(1, StreamIds.MAX)),
                sent(SERVER, () -> new Frame.WindowUpdate(StreamIds.MAX, 1)),
                sent(CLIENT, () -> new Frame.WindowUpdate(0, 262_144)),
                sent(SERVER, () -> new Frame.WindowUpdate(0, StreamIds.MAX)),
                sent(SERVER, () -> Frame.Close.half(1)),
                sent(CLIENT, () -> Frame.Close.reset(1, ErrorCode.LOCAL_SERVICE_UNREACHABLE)),
                sent(CLIENT, () -> new Frame.Ping(-1L)),
                sent(SERVER, () -> new Frame.Pong(0x0102_0304_0506_0708L)),
                sent(CLIENT, () -> new Frame.Error(new ErrorBody(ErrorCode.PROTOCOL_ERROR, "Bad frame.", null))),
                sent(SERVER, () -> new Frame.Error(new ErrorBody(ErrorCode.PORT_UNAVAILABLE, "Taken.", "default"))));
    }

    @Test
    void samplesCoverEveryFrameTypeExceptTheUnsupportedOnes() {
        Set<FrameType> covered = sentFrames()
                .map(arguments -> sample(arguments).get())
                .peek(Frame::release)
                .map(Frame::type)
                .collect(Collectors.toCollection(() -> EnumSet.noneOf(FrameType.class)));

        assertThat(covered).isEqualTo(EnumSet.complementOf(EnumSet.of(FrameType.HEADERS, FrameType.GOAWAY)));
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("sentFrames")
    void roundTripsThroughTheOtherSide(Role sender, String name, Supplier<Frame> sample) {
        Frame expected = sample.get();
        byte[] expectedPayload = expected instanceof Frame.Data data ? ByteBufUtil.getBytes(data.payload()) : null;
        Frame original = sample.get();

        ByteBuf message = codec(sender).encode(original, ALLOC);
        Frame decoded = codec(receiver(sender)).decode(message);
        try {
            if (decoded instanceof Frame.Data data) {
                assertThat(data.streamId()).isEqualTo(expected.streamId());
                assertThat(ByteBufUtil.getBytes(data.payload())).isEqualTo(expectedPayload);
            } else {
                assertThat(decoded).isEqualTo(expected);
            }
        } finally {
            decoded.release();
            message.release();
            expected.release();
        }

        assertThat(message.refCnt()).isZero();
        assertThat(original.refCnt()).isEqualTo(original instanceof Frame.Data ? 0 : 1);
    }

    @Test
    void decodeLeavesTheCallersIndexesAlone() {
        ByteBuf message = CLIENT_CODEC.encode(new Frame.Data(1, ascii("abc")), ALLOC);
        int readerIndex = message.readerIndex();
        int writerIndex = message.writerIndex();

        SERVER_CODEC.decode(message).release();

        assertThat(message.readerIndex()).isEqualTo(readerIndex);
        assertThat(message.writerIndex()).isEqualTo(writerIndex);
        message.release();
        assertThat(message.refCnt()).isZero();
    }

    @Test
    void decodeLeavesTheCallersIndexesAloneWhenItFails() {
        ByteBuf message = ALLOC.buffer().writeBytes(new byte[] {0, 0, 0, 1, (byte) 0xFF, 0, 0, 0, 0, 0});

        catchThrowableOfType(ProtocolException.class, () -> SERVER_CODEC.decode(message));

        assertThat(message.readerIndex()).isZero();
        assertThat(message.writerIndex()).isEqualTo(10);
        assertThat(message.refCnt()).isEqualTo(1);
        message.release();
        assertThat(message.refCnt()).isZero();
    }

    @Test
    void decodedDataIsARetainedSliceOfTheMessageNotACopy() {
        ByteBuf message = ALLOC.buffer().writeBytes(new byte[] {0, 0, 0, 5, 5, 0, 0, 0, 0, 2, 'h', 'i'});

        Frame.Data data = (Frame.Data) SERVER_CODEC.decode(message);
        message.setByte(10, 'H');

        assertThat(data.payload().toString(US_ASCII)).isEqualTo("Hi");
        assertThat(message.refCnt()).isEqualTo(2);
        message.release();
        assertThat(data.refCnt()).isEqualTo(1);
        data.release();
        assertThat(message.refCnt()).isZero();
    }

    @Test
    void decodeReadsAMessageThatStartsPastIndexZero() {
        ByteBuf message =
                ALLOC.buffer().writeBytes(new byte[] {9, 9, 0, 0, 0, 0, 8, 0, 0, 0, 0, 8, 1, 2, 3, 4, 5, 6, 7, 8});
        message.skipBytes(2);

        Frame frame = CLIENT_CODEC.decode(message);

        assertThat(frame).isEqualTo(new Frame.Ping(0x0102_0304_0506_0708L));
        message.release();
    }

    @Test
    void encodedDataHoldsThePayloadBufferItself() {
        ByteBuf payload = ascii("hello");

        ByteBuf message = SERVER_CODEC.encode(new Frame.Data(1, payload), ALLOC);
        payload.setByte(0, 'J');

        assertThat(message).isInstanceOf(CompositeByteBuf.class);
        assertThat(((CompositeByteBuf) message).numComponents()).isEqualTo(2);
        assertThat(message.toString(10, 5, US_ASCII)).isEqualTo("Jello");
        assertThat(payload.refCnt()).isEqualTo(1);
        message.release();
        assertThat(payload.refCnt()).isZero();
    }

    @Test
    void encodedDataHeaderIsTheFrameHeader() {
        ByteBuf message = SERVER_CODEC.encode(new Frame.Data(7, ascii("hello")), ALLOC);

        assertThat(ByteBufUtil.hexDump(message)).isEqualTo("00000007" + "05" + "00" + "00000005" + "68656c6c6f");
        message.release();
    }

    @Test
    void encodedCloseResetCarriesTheCodeInAscii() {
        ByteBuf message = CLIENT_CODEC.encode(Frame.Close.reset(2, ErrorCode.PEER_RESET), ALLOC);

        assertThat(ByteBufUtil.hexDump(message))
                .isEqualTo(
                        "00000002" + "07" + "01" + "0000000a" + ByteBufUtil.hexDump("PEER_RESET".getBytes(US_ASCII)));
        message.release();
    }

    @ParameterizedTest
    @EnumSource(value = Role.class)
    void refusesToEncodeEmptyData(Role role) {
        ByteBuf payload = ALLOC.buffer(0);

        assertThatIllegalArgumentException().isThrownBy(() -> codec(role).encode(new Frame.Data(1, payload), ALLOC));
        assertThat(payload.refCnt()).isZero();
    }

    @Test
    void refusesToEncodeDataOver64KiB() {
        ByteBuf payload = ALLOC.buffer().writeZero(65_537);

        assertThatIllegalArgumentException()
                .isThrownBy(() -> SERVER_CODEC.encode(new Frame.Data(1, payload), ALLOC))
                .withMessageContaining("65537");
        assertThat(payload.refCnt()).isZero();
    }

    @Test
    void refusesToEncodeAFrameItsSideNeverSends() {
        Frame open = new Frame.Open(1, new Open(TUNNEL_ID, TunnelKind.TCP, new RemoteAddress("203.0.113.9", 1)));
        Frame hello = new Frame.Hello(
                new Hello(new ClientInfo("a", "1", null, null), List.of(), new Windows(65_536, 65_536)));
        Frame registered = new Frame.Registered(
                new Registered(List.of(RegisteredTunnel.of("a", TUNNEL_ID, TunnelKind.TCP, "localhost", 21_000))));

        assertThatIllegalArgumentException().isThrownBy(() -> CLIENT_CODEC.encode(open, ALLOC));
        assertThatIllegalArgumentException().isThrownBy(() -> SERVER_CODEC.encode(hello, ALLOC));
        assertThatIllegalArgumentException().isThrownBy(() -> CLIENT_CODEC.encode(registered, ALLOC));
    }

    @Test
    void refusesToEncodeUnsupportedFrames() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> SERVER_CODEC.encode(new Frame.Unsupported(0, FrameType.GOAWAY), ALLOC));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> CLIENT_CODEC.encode(new Frame.Unsupported(1, FrameType.HEADERS), ALLOC));
    }

    @Test
    void decodeFailuresAreSessionErrorsThatNeverQuoteThePayload() {
        byte[] json = "{\"authtoken\":\"jb_secret-token\",\"tunnels\":[{\"ref\":\"BAD\",\"kind\":\"tcp\"}]}"
                .getBytes(US_ASCII);
        ByteBuf message = ALLOC.buffer()
                .writeInt(0)
                .writeByte(FrameType.REGISTER.code())
                .writeByte(0)
                .writeInt(json.length)
                .writeBytes(json);

        ProtocolException error = catchThrowableOfType(ProtocolException.class, () -> SERVER_CODEC.decode(message));

        assertThat(error.code()).isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThat(error.scope()).isEqualTo(ErrorScope.SESSION);
        assertThat(error.getMessage()).doesNotContain("secret").doesNotContain("BAD");
        message.release();
    }

    @Test
    void frameRecordsRefuseImpossibleFrames() {
        ByteBuf payload = ascii("x");

        assertThatIllegalArgumentException().isThrownBy(() -> new Frame.Data(0, payload));
        assertThatIllegalArgumentException().isThrownBy(() -> new Frame.WindowUpdate(1, 0));
        assertThatIllegalArgumentException().isThrownBy(() -> new Frame.WindowUpdate(-1, 1));
        assertThatIllegalArgumentException().isThrownBy(() -> Frame.Close.half(0));
        assertThatIllegalArgumentException().isThrownBy(() -> Frame.Close.reset(1, ErrorCode.AUTHTOKEN_INVALID));
        assertThatIllegalArgumentException().isThrownBy(() -> new Frame.Unsupported(1, FrameType.DATA));
        assertThatIllegalArgumentException().isThrownBy(() -> new Frame.Unsupported(0, FrameType.HEADERS));
        assertThatIllegalArgumentException().isThrownBy(() -> new Frame.Unsupported(1, FrameType.GOAWAY));
        payload.release();
    }

    @Test
    void framesWithoutABufferIgnoreReferenceCounting() {
        Frame ping = new Frame.Ping(1);

        assertThat(ping.retain()).isSameAs(ping);
        assertThat(ping.release()).isFalse();
        assertThat(ping.release(5)).isFalse();
        assertThat(ping.refCnt()).isEqualTo(1);
    }

    @Test
    void toStringNeverShowsPayloadsOrTokens() {
        Frame.Data data = new Frame.Data(1, ascii("secret-bytes"));
        Frame register = new Frame.Register(new Register(TOKEN, List.of(new TunnelRequest("a", TunnelKind.TCP, null))));

        assertThat(data.toString()).isEqualTo("Data[streamId=1, length=12]");
        assertThat(register.toString()).doesNotContain(TOKEN.substring(3));
        data.release();
    }

    private static Arguments sent(Role sender, Supplier<Frame> sample) {
        Frame probe = sample.get();
        String name = probe.getClass().getSimpleName();
        probe.release();
        return arguments(sender, name, sample);
    }

    @SuppressWarnings("unchecked")
    private static Supplier<Frame> sample(Arguments arguments) {
        return (Supplier<Frame>) arguments.get()[2];
    }

    private static FrameCodec codec(Role role) {
        return role == SERVER ? SERVER_CODEC : CLIENT_CODEC;
    }

    private static Role receiver(Role sender) {
        return sender == SERVER ? CLIENT : SERVER;
    }

    private static ByteBuf ascii(String text) {
        return ALLOC.buffer().writeBytes(text.getBytes(US_ASCII));
    }
}
