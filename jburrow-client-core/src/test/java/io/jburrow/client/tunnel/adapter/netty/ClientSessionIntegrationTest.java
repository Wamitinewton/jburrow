package io.jburrow.client.tunnel.adapter.netty;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.jburrow.client.ClientSettings;
import io.jburrow.client.TunnelHandle;
import io.jburrow.client.TunnelStartException;
import io.jburrow.client.TunnelStartException.Kind;
import io.jburrow.client.tunnel.domain.LocalTarget;
import io.jburrow.client.tunnel.domain.TunnelClosed;
import io.jburrow.client.tunnel.domain.TunnelSpec;
import io.jburrow.protocol.adapter.jackson.ControlJson;
import io.jburrow.protocol.adapter.netty.Frame;
import io.jburrow.protocol.control.ClientInfo;
import io.jburrow.protocol.control.ErrorBody;
import io.jburrow.protocol.control.Open;
import io.jburrow.protocol.control.Registered;
import io.jburrow.protocol.control.RegisteredTunnel;
import io.jburrow.protocol.control.RemoteAddress;
import io.jburrow.protocol.control.ServerHello;
import io.jburrow.protocol.control.ServerInfo;
import io.jburrow.protocol.control.SessionLimits;
import io.jburrow.protocol.control.TunnelKind;
import io.jburrow.protocol.control.TunnelRequest;
import io.jburrow.protocol.control.UpgradeRefusal;
import io.jburrow.protocol.testsupport.RefusingServer;
import io.jburrow.protocol.testsupport.ScriptedPeer;
import io.jburrow.protocol.wire.ErrorCode;
import io.jburrow.protocol.wire.FrameLimits;
import io.jburrow.protocol.wire.ProtocolVersion;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.websocketx.WebSocketCloseStatus;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The real client against {@link ScriptedPeer} playing the server, so the client's state machine and its reactions to
 * every server behaviour are proven without the real server's implementation hiding a bug.
 */
class ClientSessionIntegrationTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final String TOKEN = "jb_" + "t".repeat(43);
    private static final UUID SESSION_ID = UUID.fromString("0b8e5c1e-7c55-4c46-9a3e-2f1d0c9e4b7a");
    private static final UUID TUNNEL_ID = UUID.fromString("5d0f4f0e-31a6-4a39-8d51-0c0f7f1f6c23");
    private static final RemoteAddress VISITOR = new RemoteAddress("203.0.113.9", 51_234);
    private static final SessionLimits LIMITS = new SessionLimits(FrameLimits.MAX_DATA_PAYLOAD, 1, 1, 15_000, 45_000);
    private static final ClientInfo CLIENT = new ClientInfo("jburrow-test", "1.0.0", "linux", "amd64");
    private static final int MEBIBYTE = 1024 * 1024;

    private final ExecutorService threads = Executors.newVirtualThreadPerTaskExecutor();
    private final RecordingEvents events = new RecordingEvents();
    private ScriptedPeer peer;
    private NettyTunnelClient client;
    private ServerSocket local;

    @AfterEach
    void stop() throws IOException {
        if (client != null) {
            client.close();
        }
        if (peer != null) {
            peer.close();
        }
        if (local != null) {
            local.close();
        }
        threads.shutdownNow();
    }

    // The handshake.

    @Test
    void helloAndRegisterArePipelinedAndTheReplyEstablishesTheSession() throws Exception {
        peer = ScriptedPeer.listen(0, ProtocolVersion.CURRENT);
        Future<TunnelHandle> start = startAsync(client(peer.uri(), LocalTarget.of("localhost", 22)));

        Frame.Hello hello = peer.expect(Frame.Hello.class, TIMEOUT);
        Frame.Register register = peer.expect(Frame.Register.class, TIMEOUT);
        assertThat(hello.message().client()).isEqualTo(CLIENT);
        assertThat(hello.message().capabilities()).isEmpty();
        assertThat(register.message().authtoken()).isEqualTo(TOKEN);
        assertThat(register.message().tunnels()).containsExactly(new TunnelRequest("default", TunnelKind.TCP, null));
        assertThat(start).isNotDone();

        peer.send(serverHello(LIMITS, List.of("a-capability-nobody-offered")));
        peer.send(new Frame.Registered(new Registered(List.of(registered()))));
        TunnelHandle handle = start.get(TIMEOUT.toSeconds(), TimeUnit.SECONDS);

        assertThat(handle.sessionId()).isEqualTo(SESSION_ID.toString());
        assertThat(handle.tunnels()).containsExactly(registered());
        assertThat(events.events()).containsExactly("established " + SESSION_ID + " tcp://localhost:21000");
    }

    @Test
    void theUpgradeOffersTheSubprotocolAndIdentifiesTheClientWithoutAnOrigin() throws Exception {
        establish(LocalTarget.of("localhost", 22));

        HttpHeaders upgrade = peer.upgradeRequestHeaders();

        assertThat(upgrade.get(HttpHeaderNames.SEC_WEBSOCKET_PROTOCOL)).isEqualTo(ProtocolVersion.CURRENT);
        assertThat(upgrade.get(HttpHeaderNames.USER_AGENT)).isEqualTo("jburrow-test/1.0.0 (linux; amd64)");
        assertThat(upgrade.contains(HttpHeaderNames.ORIGIN)).isFalse();
        assertThat(upgrade.contains(HttpHeaderNames.SEC_WEBSOCKET_EXTENSIONS)).isFalse();
    }

    @Test
    void anUnsupportedVersionRefusalCarriesItsCodeAndTheServersVersions() throws Exception {
        String body = refusalBody(UpgradeRefusal.unsupportedVersion(
                "This server speaks jburrow.v1-draft.2. Upgrade the jburrow CLI.",
                List.of("jburrow.v1-draft.2"),
                Instant.now(),
                ScriptedPeer.CONNECT_PATH));

        try (RefusingServer server = RefusingServer.start(400, "application/json", body)) {
            TunnelStartException failure = failure(startAsync(client(server.uri(), LocalTarget.of("localhost", 22))));

            assertThat(failure.kind()).isEqualTo(Kind.UPGRADE_REFUSED);
            assertThat(failure.code()).contains(ErrorCode.UNSUPPORTED_PROTOCOL_VERSION);
            assertThat(failure.supportedVersions()).containsExactly("jburrow.v1-draft.2");
            assertThat(failure).hasMessage("This server speaks jburrow.v1-draft.2. Upgrade the jburrow CLI.");
        }
    }

    @Test
    void aCapacityRefusalCarriesItsCode() throws Exception {
        String body = refusalBody(UpgradeRefusal.of(
                ErrorCode.SERVER_AT_CAPACITY, "The server is full.", Instant.now(), ScriptedPeer.CONNECT_PATH));

        try (RefusingServer server = RefusingServer.start(503, "application/json", body)) {
            TunnelStartException failure = failure(startAsync(client(server.uri(), LocalTarget.of("localhost", 22))));

            assertThat(failure.kind()).isEqualTo(Kind.UPGRADE_REFUSED);
            assertThat(failure.code()).contains(ErrorCode.SERVER_AT_CAPACITY);
            assertThat(failure.supportedVersions()).isEmpty();
        }
    }

    @Test
    void aRefusalWithoutAJBurrowBodyHasNoCodeAndNamesTheStatus() throws Exception {
        try (RefusingServer server = RefusingServer.start(404, "text/html", "<html>Not Found</html>")) {
            TunnelStartException failure = failure(startAsync(client(server.uri(), LocalTarget.of("localhost", 22))));

            assertThat(failure.kind()).isEqualTo(Kind.UPGRADE_REFUSED);
            assertThat(failure.code()).isEmpty();
            assertThat(failure).hasMessageContaining("HTTP 404");
        }
    }

    @Test
    void anUpgradeWithoutTheOfferedSubprotocolFailsTheConnection() throws Exception {
        peer = ScriptedPeer.listen(0, "jburrow.v1-draft.99");

        TunnelStartException failure = failure(startAsync(client(peer.uri(), LocalTarget.of("localhost", 22))));

        assertThat(failure.kind()).isEqualTo(Kind.LOCAL_ERROR);
        assertThat(failure.code()).contains(ErrorCode.PROTOCOL_ERROR);
        assertThat(failure).hasMessageContaining("subprotocol");
    }

    @Test
    void nothingListeningIsAConnectFailure() throws Exception {
        int port = freePort();

        TunnelStartException failure = failure(startAsync(
                client(URI.create("ws://127.0.0.1:" + port + "/v1/connect"), LocalTarget.of("localhost", 22))));

        assertThat(failure.kind()).isEqualTo(Kind.CONNECT_FAILED);
    }

    @Test
    void anErrorInsteadOfHelloFailsTheStartWithItsCode() throws Exception {
        peer = ScriptedPeer.listen(0, ProtocolVersion.CURRENT);
        Future<TunnelHandle> start = startAsync(client(peer.uri(), LocalTarget.of("localhost", 22)));
        peer.expect(Frame.Hello.class, TIMEOUT);
        peer.expect(Frame.Register.class, TIMEOUT);

        peer.send(Frame.Error.of(ErrorCode.AUTHTOKEN_INVALID, "The authtoken is not valid."));
        TunnelStartException failure = failure(start);

        assertThat(failure.kind()).isEqualTo(Kind.SERVER_ERROR);
        assertThat(failure.code()).contains(ErrorCode.AUTHTOKEN_INVALID);
        assertThat(failure).hasMessage("The authtoken is not valid.");
        assertThat(events.events()).isEmpty();
    }

    @Test
    void aServersMessageReachesTheUserWithoutControlCharacters() throws Exception {
        peer = ScriptedPeer.listen(0, ProtocolVersion.CURRENT);
        Future<TunnelHandle> start = startAsync(client(peer.uri(), LocalTarget.of("localhost", 22)));
        peer.expect(Frame.Hello.class, TIMEOUT);
        peer.expect(Frame.Register.class, TIMEOUT);

        peer.send(new Frame.Error(new ErrorBody(ErrorCode.AUTHTOKEN_INVALID, "bad\u001b[2Jtoken‮!", null)));

        assertThat(failure(start)).hasMessage("bad?[2Jtoken?!");
    }

    @Test
    void aReplyBeforeTheServersHelloIsAProtocolError() throws Exception {
        peer = ScriptedPeer.listen(0, ProtocolVersion.CURRENT);
        Future<TunnelHandle> start = startAsync(client(peer.uri(), LocalTarget.of("localhost", 22)));
        peer.expect(Frame.Hello.class, TIMEOUT);
        peer.expect(Frame.Register.class, TIMEOUT);

        peer.send(new Frame.Registered(new Registered(List.of(registered()))));

        peer.expectError(ErrorCode.PROTOCOL_ERROR, TIMEOUT);
        TunnelStartException failure = failure(start);
        assertThat(failure.kind()).isEqualTo(Kind.LOCAL_ERROR);
        assertThat(failure.code()).contains(ErrorCode.PROTOCOL_ERROR);
    }

    @Test
    void aReplyThatDoesNotAnswerTheRequestIsAProtocolError() throws Exception {
        peer = ScriptedPeer.listen(0, ProtocolVersion.CURRENT);
        Future<TunnelHandle> start = startAsync(client(peer.uri(), LocalTarget.of("localhost", 22)));
        peer.expect(Frame.Hello.class, TIMEOUT);
        peer.expect(Frame.Register.class, TIMEOUT);
        peer.send(serverHello(LIMITS, List.of()));

        peer.send(new Frame.Registered(
                new Registered(List.of(RegisteredTunnel.of("other", TUNNEL_ID, TunnelKind.TCP, "localhost", 21_000)))));

        peer.expectError(ErrorCode.PROTOCOL_ERROR, TIMEOUT);
        assertThat(failure(start).code()).contains(ErrorCode.PROTOCOL_ERROR);
    }

    @Test
    void aSecondHelloIsAProtocolError() throws Exception {
        peer = ScriptedPeer.listen(0, ProtocolVersion.CURRENT);
        Future<TunnelHandle> start = startAsync(client(peer.uri(), LocalTarget.of("localhost", 22)));
        peer.expect(Frame.Hello.class, TIMEOUT);
        peer.expect(Frame.Register.class, TIMEOUT);
        peer.send(serverHello(LIMITS, List.of()));

        peer.send(serverHello(LIMITS, List.of()));

        peer.expectError(ErrorCode.PROTOCOL_ERROR, TIMEOUT);
        assertThat(failure(start).kind()).isEqualTo(Kind.LOCAL_ERROR);
    }

    @Test
    void aServerThatNeverAnswersTimesOutTheStart() throws Exception {
        peer = ScriptedPeer.listen(0, ProtocolVersion.CURRENT);
        Future<TunnelHandle> start = startAsync(
                client(peer.uri(), LocalTarget.of("localhost", 22), HostResolver.SYSTEM, Duration.ofMillis(500)));
        peer.expect(Frame.Hello.class, TIMEOUT);
        peer.expect(Frame.Register.class, TIMEOUT);

        TunnelStartException failure = failure(start);

        assertThat(failure.kind()).isEqualTo(Kind.TIMEOUT);
        assertThat(failure).hasMessageContaining("500 ms");
    }

    @Test
    void aClientStartsOnce() throws Exception {
        establish(LocalTarget.of("localhost", 22));

        assertThatThrownBy(client::start).isInstanceOf(IllegalStateException.class);
    }

    // Frames out of order or malformed, once established.

    @Test
    void aGoawayIsAProtocolErrorInThisDraft() throws Exception {
        TunnelHandle handle = establish(LocalTarget.of("localhost", 22));

        peer.sendRaw("00 00 00 00 0a 00 00 00 00 00");

        peer.expectError(ErrorCode.PROTOCOL_ERROR, TIMEOUT);
        assertClosed(handle, TunnelClosed.Reason.LOCAL_ERROR, ErrorCode.PROTOCOL_ERROR);
    }

    @Test
    void aWindowUpdateIsAProtocolErrorInThisDraft() throws Exception {
        establish(LocalTarget.of("localhost", 22));

        peer.send(new Frame.WindowUpdate(1, 1_024));

        peer.expectError(ErrorCode.PROTOCOL_ERROR, TIMEOUT);
    }

    @Test
    void aFrameTheCodecRefusesEndsTheSessionWithItsCode() throws Exception {
        establish(LocalTarget.of("localhost", 22));

        peer.sendRaw("00 00 00 00 05 00 00 00 00 01 41");

        peer.expectError(ErrorCode.PROTOCOL_ERROR, TIMEOUT);
    }

    @Test
    void dataForAStreamNeverOpenedIsAProtocolError() throws Exception {
        establish(LocalTarget.of("localhost", 22));

        peer.send(data(3, "never opened"));

        peer.expectError(ErrorCode.PROTOCOL_ERROR, TIMEOUT);
    }

    @Test
    void anOpenWhoseIdIsNotAboveTheLastIsAProtocolError() throws Exception {
        startLocalService();
        establish(localTarget());
        peer.send(open(1, TUNNEL_ID));
        try (Socket ignored = accept()) {
            peer.send(open(1, TUNNEL_ID));

            assertThat(peer.expect(Frame.Close.class, TIMEOUT)).isEqualTo(Frame.Close.reset(1, ErrorCode.CANCELLED));
            peer.expectError(ErrorCode.PROTOCOL_ERROR, TIMEOUT);
        }
    }

    @Test
    void anOpenForAnUnknownTunnelIsAProtocolError() throws Exception {
        establish(LocalTarget.of("localhost", 22));

        peer.send(open(1, UUID.randomUUID()));

        peer.expectError(ErrorCode.PROTOCOL_ERROR, TIMEOUT);
    }

    // Streams to a local service.

    @Test
    void bytesFlowBothWaysAndTheStreamIsReported() throws Exception {
        startLocalService();
        establish(localTarget());

        peer.send(open(1, TUNNEL_ID));
        peer.send(data(1, "hello"));
        try (Socket service = accept()) {
            assertThat(readFully(service.getInputStream(), 5)).isEqualTo(ascii("hello"));
            service.getOutputStream().write(ascii("world!"));
            assertThat(readData(1, 6)).isEqualTo(ascii("world!"));

            service.shutdownOutput();
            assertThat(peer.expect(Frame.Close.class, TIMEOUT)).isEqualTo(Frame.Close.half(1));
            peer.send(Frame.Close.half(1));
            assertThat(service.getInputStream().read()).isEqualTo(-1);
        }

        events.await("stream-closed", TIMEOUT);
        assertThat(events.events())
                .containsSubsequence("opened 1 default 203.0.113.9:51234", "stream-closed 1 in=5 out=6 reset=-");
    }

    @Test
    void theServerClosingItsHalfFirstStillCarriesTheLocalReply() throws Exception {
        startLocalService();
        establish(localTarget());
        peer.send(open(1, TUNNEL_ID));

        try (Socket service = accept()) {
            peer.send(data(1, "request"));
            peer.send(Frame.Close.half(1));
            assertThat(readFully(service.getInputStream(), 7)).isEqualTo(ascii("request"));
            assertThat(service.getInputStream().read()).isEqualTo(-1);

            service.getOutputStream().write(ascii("response"));
            assertThat(readData(1, 8)).isEqualTo(ascii("response"));
            service.shutdownOutput();

            assertThat(peer.expect(Frame.Close.class, TIMEOUT)).isEqualTo(Frame.Close.half(1));
            assertThat(events.await("stream-closed", TIMEOUT)).isEqualTo("stream-closed 1 in=7 out=8 reset=-");
        }
    }

    @Test
    void theLocalServiceClosingItsHalfFirstStillReceivesUntilTheServerCloses() throws Exception {
        startLocalService();
        establish(localTarget());
        peer.send(open(1, TUNNEL_ID));

        try (Socket service = accept()) {
            service.shutdownOutput();
            assertThat(peer.expect(Frame.Close.class, TIMEOUT)).isEqualTo(Frame.Close.half(1));

            peer.send(data(1, "still sending"));
            assertThat(readFully(service.getInputStream(), 13)).isEqualTo(ascii("still sending"));
            peer.send(Frame.Close.half(1));

            assertThat(service.getInputStream().read()).isEqualTo(-1);
            assertThat(events.await("stream-closed", TIMEOUT)).isEqualTo("stream-closed 1 in=13 out=0 reset=-");
        }
    }

    @Test
    void aServerResetResetsTheLocalConnection() throws Exception {
        startLocalService();
        establish(localTarget());
        peer.send(open(1, TUNNEL_ID));

        try (Socket service = accept()) {
            peer.send(Frame.Close.reset(1, ErrorCode.PEER_RESET));

            assertThatThrownBy(() -> service.getInputStream().read())
                    .isInstanceOf(SocketException.class)
                    .hasMessageContaining("reset");
            assertThat(events.await("stream-closed", TIMEOUT)).endsWith("reset=PEER_RESET");
        }
    }

    @Test
    void aLocalResetBecomesClosePeerReset() throws Exception {
        startLocalService();
        establish(localTarget());
        peer.send(open(1, TUNNEL_ID));
        Socket service = accept();

        service.setSoLinger(true, 0);
        service.close();

        assertThat(peer.expect(Frame.Close.class, TIMEOUT)).isEqualTo(Frame.Close.reset(1, ErrorCode.PEER_RESET));
    }

    @Test
    void nothingListeningLocallyResetsTheStreamAndKeepsTheSession() throws Exception {
        establish(LocalTarget.of("127.0.0.1", freePort()));

        peer.send(open(1, TUNNEL_ID));
        peer.send(data(1, "lost"));

        assertThat(peer.expect(Frame.Close.class, TIMEOUT))
                .isEqualTo(Frame.Close.reset(1, ErrorCode.LOCAL_SERVICE_UNREACHABLE));
        assertAlive();
        assertThat(events.events()).first().isEqualTo("established " + SESSION_ID + " tcp://localhost:21000");
        assertThat(events.events().get(1)).isEqualTo("opened 1 default 203.0.113.9:51234");
        // The connect can fail before the DATA arrives, which is then discarded for a closed stream.
        assertThat(events.events().get(2)).matches("stream-closed 1 in=(0|4) out=0 reset=LOCAL_SERVICE_UNREACHABLE");
    }

    @Test
    void dataAfterTheServersOwnCloseIsResetWithStreamClosed() throws Exception {
        startLocalService();
        establish(localTarget());
        peer.send(open(1, TUNNEL_ID));

        try (Socket service = accept()) {
            peer.send(Frame.Close.half(1));
            peer.send(data(1, "too late"));

            assertThat(peer.expect(Frame.Close.class, TIMEOUT))
                    .isEqualTo(Frame.Close.reset(1, ErrorCode.STREAM_CLOSED));
            assertThat(events.await("stream-closed", TIMEOUT)).endsWith("reset=STREAM_CLOSED");
        }
    }

    @Test
    void framesForAClosedStreamAreDiscardedSilently() throws Exception {
        startLocalService();
        establish(localTarget());
        peer.send(open(1, TUNNEL_ID));

        try (Socket service = accept()) {
            peer.send(Frame.Close.reset(1, ErrorCode.PEER_RESET));
            events.await("stream-closed", TIMEOUT);

            peer.send(data(1, "in flight"));
            peer.send(Frame.Close.half(1));

            assertAlive();
        }
    }

    @Test
    void localBytesAreSplitIntoFramesNoLongerThanTheServerAllows() throws Exception {
        startLocalService();
        establish(localTarget(), new SessionLimits(1_000, 1, 1, 15_000, 45_000), HostResolver.SYSTEM);
        peer.send(open(1, TUNNEL_ID));
        byte[] reply = randomBytes(5_500, 1);

        try (Socket service = accept()) {
            service.getOutputStream().write(reply);

            ByteArrayOutputStream received = new ByteArrayOutputStream();
            while (received.size() < reply.length) {
                Frame.Data frame = peer.expect(Frame.Data.class, TIMEOUT);
                try {
                    assertThat(frame.payload().readableBytes()).isBetween(1, 1_000);
                    received.writeBytes(bytes(frame.payload()));
                } finally {
                    frame.release();
                }
            }
            assertThat(received.toByteArray()).isEqualTo(reply);
        }
    }

    @Test
    void dataHeldWhileConnectingPausesReadingPastItsLimitThenArrivesInOrder() throws Exception {
        startLocalService();
        HostResolver slow = host -> {
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return List.of(InetAddress.getLoopbackAddress());
        };
        establish(LocalTarget.of("slow.example.test", local.getLocalPort()), LIMITS, slow);
        byte[] upload = randomBytes(MEBIBYTE, 2);

        peer.send(open(1, TUNNEL_ID));
        Future<?> sending = threads.submit(() -> {
            for (int offset = 0; offset < upload.length; offset += FrameLimits.MAX_DATA_PAYLOAD) {
                peer.send(new Frame.Data(1, Unpooled.wrappedBuffer(upload, offset, FrameLimits.MAX_DATA_PAYLOAD)));
            }
            return null;
        });

        awaitTrue(client::isControlReadPaused);
        try (Socket service = accept()) {
            byte[] received = readFully(service.getInputStream(), upload.length);
            assertThat(sha256(received)).isEqualTo(sha256(upload));
            sending.get(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
            awaitTrue(() -> !client.isControlReadPaused());
            assertAlive();
        }
    }

    // How a session ends.

    @Test
    void aSilentServerIsDeclaredDeadAfterTheIdleTimeout() throws Exception {
        TunnelHandle handle = establish(
                LocalTarget.of("localhost", 22), new SessionLimits(65_536, 1, 1, 1_000, 2_000), HostResolver.SYSTEM);
        peer.silence();

        Frame.Ping ping = peer.expect(Frame.Ping.class, Duration.ofSeconds(3));
        assertThat(ping.opaque()).isEqualTo(1);

        ErrorBody error = peer.expectError(ErrorCode.HEARTBEAT_TIMEOUT, Duration.ofSeconds(5));
        assertThat(error.message()).contains("2000 ms");
        assertClosed(handle, TunnelClosed.Reason.HEARTBEAT_TIMEOUT, ErrorCode.HEARTBEAT_TIMEOUT);
    }

    @Test
    void aServerPingIsAnsweredWithItsOwnBytes() throws Exception {
        establish(LocalTarget.of("localhost", 22));
        peer.silence();

        peer.send(new Frame.Ping(0x0102_0304_0506_0708L));

        assertThat(peer.expect(Frame.Pong.class, TIMEOUT).opaque()).isEqualTo(0x0102_0304_0506_0708L);
    }

    @Test
    void closingTheHandleSendsANormalCloseAndReportsUserClosed() throws Exception {
        TunnelHandle handle = establish(LocalTarget.of("localhost", 22));

        handle.close();

        assertThat(peer.expectClosed(TIMEOUT)).isEqualTo(WebSocketCloseStatus.NORMAL_CLOSURE);
        assertClosed(handle, TunnelClosed.Reason.USER_CLOSED, null);
        assertThat(events.events()).last().isEqualTo("closed USER_CLOSED -");
    }

    @Test
    void closingWithAnOpenStreamCancelsItFirst() throws Exception {
        startLocalService();
        TunnelHandle handle = establish(localTarget());
        peer.send(open(1, TUNNEL_ID));

        try (Socket service = accept()) {
            handle.close();

            assertThat(peer.expect(Frame.Close.class, TIMEOUT)).isEqualTo(Frame.Close.reset(1, ErrorCode.CANCELLED));
            assertThat(peer.expectClosed(TIMEOUT)).isEqualTo(WebSocketCloseStatus.NORMAL_CLOSURE);
            assertThatThrownBy(() -> service.getInputStream().read()).isInstanceOf(SocketException.class);
        }
        assertClosed(handle, TunnelClosed.Reason.USER_CLOSED, null);
        assertThat(events.events())
                .containsSubsequence("stream-closed 1 in=0 out=0 reset=CANCELLED", "closed USER_CLOSED -");
    }

    @Test
    void closingTheClientClosesTheSessionInOrder() throws Exception {
        TunnelHandle handle = establish(LocalTarget.of("localhost", 22));

        client.close();

        assertThat(handle.closed().toCompletableFuture().isDone()).isTrue();
        assertClosed(handle, TunnelClosed.Reason.USER_CLOSED, null);
        assertThat(peer.expectClosed(TIMEOUT)).isEqualTo(WebSocketCloseStatus.NORMAL_CLOSURE);
    }

    @Test
    void aServerErrorEndsTheSessionWithoutAReply() throws Exception {
        TunnelHandle handle = establish(LocalTarget.of("localhost", 22));

        peer.send(Frame.Error.of(ErrorCode.SERVER_SHUTTING_DOWN, "The server is shutting down."));

        assertThat(peer.expectClosed(TIMEOUT)).isEqualTo(WebSocketCloseStatus.ABNORMAL_CLOSURE);
        TunnelClosed closed = assertClosed(handle, TunnelClosed.Reason.SERVER_ERROR, ErrorCode.SERVER_SHUTTING_DOWN);
        assertThat(closed.message()).isEqualTo("The server is shutting down.");
    }

    @Test
    void aDroppedConnectionIsAConnectionLost() throws Exception {
        TunnelHandle handle = establish(LocalTarget.of("localhost", 22));

        peer.dropConnection();

        assertClosed(handle, TunnelClosed.Reason.CONNECTION_LOST, null);
        assertThat(events.events()).last().isEqualTo("closed CONNECTION_LOST -");
    }

    // Helpers.

    private NettyTunnelClient client(URI server, LocalTarget target) {
        return client(server, target, HostResolver.SYSTEM, TIMEOUT);
    }

    private NettyTunnelClient client(URI server, LocalTarget target, HostResolver resolver, Duration handshake) {
        ClientSettings settings = new ClientSettings(
                server, TOKEN, List.of(TunnelSpec.tcp("default", target)), events, CLIENT, Optional.empty(), handshake);
        client = new NettyTunnelClient(settings, resolver);
        return client;
    }

    private Future<TunnelHandle> startAsync(NettyTunnelClient started) {
        return threads.submit(started::start);
    }

    private TunnelHandle establish(LocalTarget target) throws Exception {
        return establish(target, LIMITS, HostResolver.SYSTEM);
    }

    private TunnelHandle establish(LocalTarget target, SessionLimits limits, HostResolver resolver) throws Exception {
        peer = ScriptedPeer.listen(0, ProtocolVersion.CURRENT);
        Future<TunnelHandle> start = startAsync(client(peer.uri(), target, resolver, TIMEOUT));
        peer.expect(Frame.Hello.class, TIMEOUT);
        peer.expect(Frame.Register.class, TIMEOUT);
        peer.send(serverHello(limits, List.of()));
        peer.send(new Frame.Registered(new Registered(List.of(registered()))));
        return start.get(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
    }

    private static TunnelStartException failure(Future<TunnelHandle> start) throws Exception {
        try {
            start.get(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof TunnelStartException failure) {
                return failure;
            }
            throw e;
        }
        throw new AssertionError("start() succeeded");
    }

    private static TunnelClosed assertClosed(TunnelHandle handle, TunnelClosed.Reason reason, ErrorCode code)
            throws Exception {
        TunnelClosed closed = handle.closed().toCompletableFuture().get(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        assertThat(closed.reason()).isEqualTo(reason);
        assertThat(closed.code()).isEqualTo(Optional.ofNullable(code));
        return closed;
    }

    /** A {@code PING} comes back as a {@code PONG}, with nothing else before it. */
    private void assertAlive() {
        peer.silence();
        peer.send(new Frame.Ping(99));
        assertThat(peer.expect(Frame.Pong.class, TIMEOUT).opaque()).isEqualTo(99);
    }

    private void startLocalService() throws IOException {
        local = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        local.setSoTimeout((int) TIMEOUT.toMillis());
    }

    private LocalTarget localTarget() {
        return LocalTarget.of(InetAddress.getLoopbackAddress().getHostAddress(), local.getLocalPort());
    }

    private Socket accept() throws IOException {
        Socket socket = local.accept();
        socket.setSoTimeout((int) TIMEOUT.toMillis());
        return socket;
    }

    private byte[] readData(int streamId, int length) {
        ByteArrayOutputStream received = new ByteArrayOutputStream();
        while (received.size() < length) {
            Frame.Data frame = peer.expect(Frame.Data.class, TIMEOUT);
            try {
                assertThat(frame.streamId()).isEqualTo(streamId);
                received.writeBytes(bytes(frame.payload()));
            } finally {
                frame.release();
            }
        }
        return received.toByteArray();
    }

    private static ServerHello serverHelloMessage(SessionLimits limits, List<String> capabilities) {
        return new ServerHello(SESSION_ID, new ServerInfo("jburrow-server", "0.2.0"), capabilities, limits);
    }

    private static Frame.ServerHello serverHello(SessionLimits limits, List<String> capabilities) {
        return new Frame.ServerHello(serverHelloMessage(limits, capabilities));
    }

    private static RegisteredTunnel registered() {
        return RegisteredTunnel.of("default", TUNNEL_ID, TunnelKind.TCP, "localhost", 21_000);
    }

    private static Frame.Open open(int streamId, UUID tunnelId) {
        return new Frame.Open(streamId, new Open(tunnelId, TunnelKind.TCP, VISITOR));
    }

    private static Frame.Data data(int streamId, String text) {
        return new Frame.Data(streamId, Unpooled.copiedBuffer(text, StandardCharsets.US_ASCII));
    }

    private static String refusalBody(UpgradeRefusal refusal) {
        return new String(ControlJson.create().writeRefusal(refusal), StandardCharsets.UTF_8);
    }

    private static byte[] ascii(String text) {
        return text.getBytes(StandardCharsets.US_ASCII);
    }

    private static byte[] bytes(ByteBuf buffer) {
        byte[] bytes = new byte[buffer.readableBytes()];
        buffer.getBytes(buffer.readerIndex(), bytes);
        return bytes;
    }

    private static byte[] readFully(InputStream in, int length) throws IOException {
        byte[] bytes = in.readNBytes(length);
        assertThat(bytes).hasSize(length);
        return bytes;
    }

    private static byte[] randomBytes(int length, long seed) {
        byte[] bytes = new byte[length];
        new Random(seed).nextBytes(bytes);
        return bytes;
    }

    private static byte[] sha256(byte[] bytes) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(bytes);
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return socket.getLocalPort();
        }
    }

    @FunctionalInterface
    private interface Condition {
        boolean holds() throws Exception;
    }

    private static void awaitTrue(Condition condition) throws Exception {
        long deadline = System.nanoTime() + TIMEOUT.toNanos();
        while (!condition.holds()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("The condition didn't hold within " + TIMEOUT);
            }
            Thread.sleep(5);
        }
    }
}
