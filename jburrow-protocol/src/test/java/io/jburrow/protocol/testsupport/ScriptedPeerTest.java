package io.jburrow.protocol.testsupport;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.jburrow.protocol.adapter.netty.Frame;
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
import io.jburrow.protocol.wire.ErrorCode;
import io.jburrow.protocol.wire.FrameType;
import io.jburrow.protocol.wire.ProtocolVersion;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.handler.codec.http.websocketx.WebSocketCloseStatus;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** A scripted client and a scripted server, over a real loopback socket. */
class ScriptedPeerTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);
    private static final Duration QUIET = Duration.ofMillis(200);
    private static final UUID TUNNEL_ID = UUID.fromString("5d0f4f0e-31a6-4a39-8d51-0c0f7f1f6c23");

    private ScriptedPeer server;
    private ScriptedPeer client;

    @BeforeEach
    void connect() {
        server = ScriptedPeer.listen(0, ProtocolVersion.CURRENT);
        client = ScriptedPeer.connect(server.uri(), ProtocolVersion.CURRENT);
    }

    @AfterEach
    void close() {
        client.close();
        server.close();
    }

    @Test
    void theClientSendsEveryFrameTypeItMay() {
        server.silence();
        Hello hello = new Hello(new ClientInfo("jburrow-cli", "0.2.0", null, null), List.of());
        Register register = new Register("jb_example", List.of(new TunnelRequest("default", TunnelKind.TCP, null)));

        client.send(new Frame.Hello(hello));
        client.send(new Frame.Register(register));
        client.send(new Frame.Data(1, ascii("ping")));
        client.send(new Frame.WindowUpdate(1, 1024));
        client.send(Frame.Close.half(1));
        client.send(Frame.Close.reset(1, ErrorCode.LOCAL_SERVICE_UNREACHABLE));
        client.send(new Frame.Ping(1));
        client.send(new Frame.Pong(2));
        client.send(Frame.Error.of(ErrorCode.PROTOCOL_ERROR, "Bad frame."));

        assertThat(server.expect(Frame.Hello.class, TIMEOUT).message()).isEqualTo(hello);
        assertThat(server.expect(Frame.Register.class, TIMEOUT).message()).isEqualTo(register);
        Frame.Data data = server.expect(Frame.Data.class, TIMEOUT);
        assertThat(data.payload().toString(US_ASCII)).isEqualTo("ping");
        data.release();
        assertThat(server.expect(Frame.WindowUpdate.class, TIMEOUT).increment()).isEqualTo(1024);
        assertThat(server.expect(Frame.Close.class, TIMEOUT).reset()).isEmpty();
        assertThat(server.expect(Frame.Close.class, TIMEOUT).reset()).contains(ErrorCode.LOCAL_SERVICE_UNREACHABLE);
        assertThat(server.expect(Frame.Ping.class, TIMEOUT).opaque()).isEqualTo(1);
        assertThat(server.expect(Frame.Pong.class, TIMEOUT).opaque()).isEqualTo(2);
        assertThat(server.expect(Frame.Error.class, TIMEOUT).body().code()).isEqualTo(ErrorCode.PROTOCOL_ERROR);
    }

    @Test
    void theServerSendsEveryFrameTypeItMay() {
        client.silence();
        ServerHello hello = new ServerHello(
                UUID.randomUUID(),
                new ServerInfo("jburrow-server", "0.2.0"),
                List.of(),
                new SessionLimits(65_536, 1, 1, 15_000, 45_000));
        Registered registered =
                new Registered(List.of(RegisteredTunnel.of("default", TUNNEL_ID, TunnelKind.TCP, "localhost", 21_037)));
        Open open = new Open(TUNNEL_ID, TunnelKind.TCP, new RemoteAddress("127.0.0.1", 50_000));

        server.send(new Frame.ServerHello(hello));
        server.send(new Frame.Registered(registered));
        server.send(new Frame.Open(1, open));
        server.send(new Frame.Data(1, ByteBufAllocator.DEFAULT.buffer().writeZero(65_536)));
        server.send(Frame.Close.half(1));
        server.send(new Frame.Ping(3));
        server.send(new Frame.Pong(4));

        assertThat(client.expect(Frame.ServerHello.class, TIMEOUT).message()).isEqualTo(hello);
        assertThat(client.expect(Frame.Registered.class, TIMEOUT).message()).isEqualTo(registered);
        assertThat(client.expect(Frame.Open.class, TIMEOUT).message()).isEqualTo(open);
        Frame.Data data = client.expect(Frame.Data.class, TIMEOUT);
        assertThat(data.payload().readableBytes()).isEqualTo(65_536);
        data.release();
        assertThat(client.expect(Frame.Close.class, TIMEOUT).streamId()).isEqualTo(1);
        assertThat(client.expect(Frame.Ping.class, TIMEOUT).opaque()).isEqualTo(3);
        assertThat(client.expect(Frame.Pong.class, TIMEOUT).opaque()).isEqualTo(4);
    }

    @Test
    void aHealthyPeerAnswersPingsItself() {
        client.silence();

        client.send(new Frame.Ping(42));

        assertThat(client.expect(Frame.Pong.class, TIMEOUT).opaque()).isEqualTo(42);
    }

    @Test
    void rawAndTextMessagesReachThePeerAsSent() {
        client.sendRaw("00000000 ff 00 00000000");
        client.sendRaw("00000001 04 00 00000000");
        client.sendText("hello");

        assertThat(server.expectRejected(TIMEOUT).code()).isEqualTo(ErrorCode.PROTOCOL_ERROR);
        assertThat(server.expect(Frame.Unsupported.class, TIMEOUT).type()).isEqualTo(FrameType.HEADERS);
        assertThat(server.expectRejected(TIMEOUT).code()).isEqualTo(ErrorCode.PROTOCOL_ERROR);
    }

    @Test
    void expectErrorWantsTheErrorThenTheClose() {
        server.send(Frame.Error.of(ErrorCode.HEARTBEAT_TIMEOUT, "Nothing received for 45 seconds."));
        server.close();

        ErrorBody error = client.expectError(ErrorCode.HEARTBEAT_TIMEOUT, TIMEOUT);

        assertThat(error.message()).isEqualTo("Nothing received for 45 seconds.");
    }

    @Test
    void aCloseCarriesItsStatus() {
        client.close();

        assertThat(server.expectClosed(TIMEOUT)).isEqualTo(WebSocketCloseStatus.NORMAL_CLOSURE);
    }

    @Test
    void anUnexpectedFrameFailsTheExpectation() {
        client.send(new Frame.Data(1, ascii("surprise")));

        assertThatThrownBy(() -> server.expect(Frame.Close.class, TIMEOUT))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("Data[streamId=1, length=8]")
                .hasMessageNotContaining("surprise");
    }

    @Test
    void pausedReadingHoldsFramesBack() {
        server.pauseReading();
        client.send(new Frame.Data(1, ascii("held")));

        assertThatExceptionOfType(AssertionError.class).isThrownBy(() -> server.expect(Frame.Data.class, QUIET));

        server.resumeReading();
        Frame.Data data = server.expect(Frame.Data.class, TIMEOUT);
        assertThat(data.payload().toString(US_ASCII)).isEqualTo("held");
        data.release();
    }

    @Test
    void aListeningPeerAcceptsOneConnection() {
        assertThatThrownBy(() -> ScriptedPeer.connect(server.uri(), ProtocolVersion.CURRENT))
                .isInstanceOf(IllegalStateException.class);
    }

    private static ByteBuf ascii(String text) {
        return ByteBufAllocator.DEFAULT.buffer().writeBytes(text.getBytes(US_ASCII));
    }
}
