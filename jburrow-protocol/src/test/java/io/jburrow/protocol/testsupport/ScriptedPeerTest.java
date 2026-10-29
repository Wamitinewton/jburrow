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
import io.jburrow.protocol.control.Windows;
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
        Hello hello = hello(ScriptedPeer.CLIENT_WINDOWS);
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
                new SessionLimits(65_536, 1, 1, 15_000, 45_000),
                ScriptedPeer.SERVER_WINDOWS);
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

    @Test
    void dataPastTheStreamWindowFailsTheTest() {
        client.send(new Frame.Hello(hello(new Windows(65_536, 1_048_576))));
        server.expect(Frame.Hello.class, TIMEOUT);

        server.send(new Frame.Data(1, zeros(65_536)));
        server.send(new Frame.Data(1, zeros(1)));

        Frame.Data first = client.expect(Frame.Data.class, TIMEOUT);
        first.release();
        assertThatThrownBy(() -> client.expect(Frame.Data.class, TIMEOUT))
                .isInstanceOf(AssertionError.class)
                .hasMessage("Flow-control violation: the implementation sent 1 bytes of DATA on stream 1 with 0 bytes"
                        + " of stream credit and 983040 bytes of session credit left (this peer announced windows"
                        + " stream 65536, session 1048576)");
    }

    @Test
    void dataPastTheSessionWindowFailsTheTest() {
        client.send(new Frame.Hello(hello(new Windows(65_536, 100_000))));
        server.expect(Frame.Hello.class, TIMEOUT);

        server.send(new Frame.Data(1, zeros(65_536)));
        server.send(new Frame.Data(3, zeros(65_536)));

        client.expect(Frame.Data.class, TIMEOUT).release();
        assertThatThrownBy(() -> client.expect(Frame.Data.class, TIMEOUT))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining(
                        "on stream 3 with 65536 bytes of stream credit and 34464 bytes of session credit");
    }

    @Test
    void anOverrunNoCallReportedFailsTheClose() {
        server.send(new Frame.Data(1, zeros(65_536)));
        server.send(new Frame.Data(1, zeros(65_536)));
        server.send(new Frame.Data(1, zeros(65_536)));
        server.send(new Frame.Data(1, zeros(65_536)));
        server.send(new Frame.Data(1, zeros(1)));
        server.silence();
        server.send(new Frame.Ping(7));
        server.expect(Frame.Pong.class, TIMEOUT);

        assertThatThrownBy(client::close)
                .isInstanceOf(AssertionError.class)
                .hasMessageStartingWith("Flow-control violation");
    }

    @Test
    void grantsRaiseTheCreditThePeerPolices() {
        client.send(new Frame.Hello(hello(new Windows(65_536, 131_072))));
        server.expect(Frame.Hello.class, TIMEOUT);
        server.send(new Frame.Data(1, zeros(65_536)));
        client.expect(Frame.Data.class, TIMEOUT).release();

        client.grant(1, 1_000);
        client.grantSession(70_000);

        assertThat(server.expect(Frame.WindowUpdate.class, TIMEOUT)).isEqualTo(new Frame.WindowUpdate(1, 1_000));
        assertThat(server.expect(Frame.WindowUpdate.class, TIMEOUT)).isEqualTo(new Frame.WindowUpdate(0, 70_000));
        assertThat(client.streamCredit(1)).isEqualTo(1_000);
        assertThat(client.sessionCredit()).isEqualTo(135_536);
        server.send(new Frame.Data(1, zeros(1_000)));
        client.expect(Frame.Data.class, TIMEOUT).release();
        assertThat(client.dataReceived(1)).isEqualTo(66_536);
        assertThat(client.dataReceived()).isEqualTo(66_536);
        assertThat(client.streamCredit(1)).isZero();
    }

    @Test
    void aWindowUpdateSentAsAFrameGrantsNothing() {
        client.send(new Frame.Hello(hello(new Windows(65_536, 131_072))));
        client.send(new Frame.WindowUpdate(1, 1_000));

        assertThat(client.streamCredit(1)).isEqualTo(65_536);
    }

    @Test
    void sendDataWaitsForTheCreditTheOtherSideGives() {
        client.send(new Frame.Hello(hello(new Windows(65_536, 131_072))));
        server.expect(Frame.Hello.class, TIMEOUT);

        server.sendData(1, zeros(65_536), TIMEOUT);
        assertThatExceptionOfType(AssertionError.class)
                .isThrownBy(() -> server.sendData(1, zeros(1_000), QUIET))
                .withMessageContaining("No credit");
        client.grant(1, 1_000);
        server.sendData(1, zeros(1_000), TIMEOUT);

        client.expect(Frame.Data.class, TIMEOUT).release();
        client.expect(Frame.Data.class, TIMEOUT).release();
        assertThat(client.dataReceived(1)).isEqualTo(66_536);
        assertThat(server.peerStreamCredit(1)).isZero();
        assertThat(server.peerSessionCredit()).isEqualTo(131_072 - 66_536);
    }

    @Test
    void dataSentAsAFrameStillSpendsThePeersOwnCredit() {
        client.send(new Frame.Hello(hello(new Windows(65_536, 131_072))));
        server.expect(Frame.Hello.class, TIMEOUT);

        server.send(new Frame.Data(1, zeros(1_000)));

        assertThat(server.peerStreamCredit(1)).isEqualTo(64_536);
        client.expect(Frame.Data.class, TIMEOUT).release();
    }

    @Test
    void ignoredWindowUpdatesStillAddCredit() {
        client.send(new Frame.Hello(hello(new Windows(65_536, 131_072))));
        server.expect(Frame.Hello.class, TIMEOUT);
        server.ignoreWindowUpdates();

        client.grant(1, 500);

        server.sendData(1, zeros(65_536), TIMEOUT);
        server.sendData(1, zeros(500), TIMEOUT);
        assertThatExceptionOfType(AssertionError.class)
                .isThrownBy(() -> server.expect(Frame.WindowUpdate.class, QUIET));
        client.expect(Frame.Data.class, TIMEOUT).release();
        client.expect(Frame.Data.class, TIMEOUT).release();
    }

    @Test
    void creditIsWithheldUnlessATestReturnsIt() {
        server.send(new Frame.Data(1, zeros(65_536)));
        server.send(new Frame.Data(1, zeros(65_536)));
        client.expect(Frame.Data.class, TIMEOUT).release();
        client.expect(Frame.Data.class, TIMEOUT).release();

        assertThatExceptionOfType(AssertionError.class)
                .isThrownBy(() -> server.expect(Frame.WindowUpdate.class, QUIET));
    }

    @Test
    void autoGrantReturnsCreditAtHalfTheWindow() {
        client.send(new Frame.Hello(hello(new Windows(131_072, 262_144))));
        server.expect(Frame.Hello.class, TIMEOUT);
        client.autoGrant();

        server.send(new Frame.Data(1, zeros(65_535)));
        client.expect(Frame.Data.class, TIMEOUT).release();
        assertThatExceptionOfType(AssertionError.class)
                .isThrownBy(() -> server.expect(Frame.WindowUpdate.class, QUIET));
        server.send(new Frame.Data(1, zeros(1)));

        assertThat(server.expect(Frame.WindowUpdate.class, TIMEOUT)).isEqualTo(new Frame.WindowUpdate(1, 65_536));
        client.expect(Frame.Data.class, TIMEOUT).release();
        assertThat(client.streamCredit(1)).isEqualTo(131_072);
    }

    @Test
    void autoGrantReturnsWhatIsAlreadyOwed() {
        client.send(new Frame.Hello(hello(new Windows(65_536, 131_072))));
        server.expect(Frame.Hello.class, TIMEOUT);
        server.send(new Frame.Data(1, zeros(40_000)));
        client.expect(Frame.Data.class, TIMEOUT).release();

        client.autoGrant();

        assertThat(server.expect(Frame.WindowUpdate.class, TIMEOUT)).isEqualTo(new Frame.WindowUpdate(1, 40_000));
    }

    @Test
    void noStreamCreditGoesBackAfterTheImplementationClosesTheStream() {
        client.send(new Frame.Hello(hello(new Windows(65_536, 131_072))));
        server.expect(Frame.Hello.class, TIMEOUT);
        client.autoGrant();

        server.send(new Frame.Data(1, zeros(30_000)));
        server.send(Frame.Close.half(1));
        server.send(new Frame.Data(3, zeros(40_000)));

        List<Frame.WindowUpdate> updates = List.of(
                server.expect(Frame.WindowUpdate.class, TIMEOUT), server.expect(Frame.WindowUpdate.class, TIMEOUT));
        assertThat(updates)
                .containsExactlyInAnyOrder(new Frame.WindowUpdate(0, 70_000), new Frame.WindowUpdate(3, 40_000));
        assertThatExceptionOfType(AssertionError.class)
                .isThrownBy(() -> server.expect(Frame.WindowUpdate.class, QUIET));
    }

    @Test
    void aServerPolicesItsOwnWindows() {
        server.send(new Frame.ServerHello(new ServerHello(
                UUID.randomUUID(),
                new ServerInfo("jburrow-server", "0.2.0"),
                List.of(),
                new SessionLimits(65_536, 1, 1, 15_000, 45_000),
                new Windows(65_536, 65_536))));
        client.expect(Frame.ServerHello.class, TIMEOUT);

        client.send(new Frame.Data(1, zeros(65_536)));
        client.send(new Frame.Data(3, zeros(1)));

        server.expect(Frame.Data.class, TIMEOUT).release();
        assertThatThrownBy(() -> server.expect(Frame.Data.class, TIMEOUT))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("with 65536 bytes of stream credit and 0 bytes of session credit");
    }

    private static Hello hello(Windows windows) {
        return new Hello(new ClientInfo("jburrow-cli", "0.2.0", null, null), List.of(), windows);
    }

    private static ByteBuf zeros(int length) {
        return ByteBufAllocator.DEFAULT.buffer(length).writeZero(length);
    }

    private static ByteBuf ascii(String text) {
        return ByteBufAllocator.DEFAULT.buffer().writeBytes(text.getBytes(US_ASCII));
    }
}
