package io.jburrow.cli;

import static org.assertj.core.api.Assertions.assertThat;

import io.jburrow.protocol.adapter.jackson.ControlJson;
import io.jburrow.protocol.adapter.netty.Frame;
import io.jburrow.protocol.control.Open;
import io.jburrow.protocol.control.Registered;
import io.jburrow.protocol.control.RegisteredTunnel;
import io.jburrow.protocol.control.RemoteAddress;
import io.jburrow.protocol.control.ServerHello;
import io.jburrow.protocol.control.ServerInfo;
import io.jburrow.protocol.control.SessionLimits;
import io.jburrow.protocol.control.TunnelKind;
import io.jburrow.protocol.control.UpgradeRefusal;
import io.jburrow.protocol.testsupport.RefusingServer;
import io.jburrow.protocol.testsupport.ScriptedPeer;
import io.jburrow.protocol.wire.ErrorCode;
import io.jburrow.protocol.wire.FrameLimits;
import io.jburrow.protocol.wire.ProtocolVersion;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.websocketx.WebSocketCloseStatus;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * The real command, through {@link JBurrowCli}'s command line, against {@link ScriptedPeer} playing the server over
 * {@code ws://127.0.0.1}. Stopping is the command's own close path, not a signal.
 */
class TcpCommandTest {

    private static final Duration TIMEOUT = CommandHarness.TIMEOUT;
    private static final String TOKEN = "jb_" + "t".repeat(43);
    private static final UUID SESSION_ID = UUID.fromString("0b8e5c1e-7c55-4c46-9a3e-2f1d0c9e4b7a");
    private static final UUID TUNNEL_ID = UUID.fromString("5d0f4f0e-31a6-4a39-8d51-0c0f7f1f6c23");
    private static final SessionLimits LIMITS = new SessionLimits(FrameLimits.MAX_DATA_PAYLOAD, 1, 1, 15_000, 45_000);
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-25T12:01:07Z"), ZoneOffset.UTC);
    private static final String NL = System.lineSeparator();

    private final CommandHarness cli = new CommandHarness(CLOCK, TOKEN);
    private final CommandHarness.ManualStop stop = cli.stop();
    private ScriptedPeer peer;
    private ServerSocket local;

    @AfterEach
    void stop() throws IOException {
        assertThat(cli.out()).doesNotContain(TOKEN);
        assertThat(cli.err()).doesNotContain(TOKEN);
        if (peer != null) {
            peer.close();
        }
        if (local != null) {
            local.close();
        }
        cli.close();
    }

    // Established sessions.

    @Test
    void anEstablishedTunnelPrintsTheVersionAndWhereItForwards() throws Exception {
        Future<Integer> command = establish("tcp", "22");

        cli.awaitOutput(NL + NL);
        assertThat(cli.lines())
                .containsExactly(
                        "jburrow " + new BuildInfo().version() + ", protocol " + ProtocolVersion.CURRENT,
                        "Forwarding  tcp://localhost:21000 -> localhost:22",
                        "");
        assertThat(cli.lines().getFirst()).endsWith(", protocol jburrow.v1-draft.2");

        stop.request();
        assertThat(cli.exitCode(command)).isZero();
        assertThat(cli.err()).isEmpty();
    }

    @Test
    void jsonOutputReportsTheRegisteredTunnel() throws Exception {
        Future<Integer> command = establish("tcp", "22", "--output", "json");

        cli.awaitOutput(NL);
        JsonNode registered = cli.jsonLines().getFirst();
        assertThat(registered.get("type").asString()).isEqualTo("registered");
        assertThat(registered.get("sessionId").asString()).isEqualTo(SESSION_ID.toString());
        JsonNode tunnel = registered.get("tunnels").get(0);
        assertThat(tunnel.get("url").asString()).isEqualTo("tcp://localhost:21000");
        assertThat(tunnel.get("local").asString()).isEqualTo("localhost:22");

        stop.request();
        assertThat(cli.exitCode(command)).isZero();
    }

    @Test
    void theTokenCanComeFromTheCommandLine() throws Exception {
        cli.environment().clear();
        Future<Integer> command = establish("tcp", "22", "--authtoken", TOKEN);

        cli.awaitOutput("Forwarding");
        stop.request();

        assertThat(cli.exitCode(command)).isZero();
    }

    @Test
    void aConnectionThroughTheTunnelIsLoggedWhenItOpensAndCloses() throws Exception {
        startEchoService();
        Future<Integer> command = establish("tcp", String.valueOf(local.getLocalPort()));

        peer.send(new Frame.Open(1, new Open(TUNNEL_ID, TunnelKind.TCP, new RemoteAddress("203.0.113.9", 51_234))));
        peer.send(new Frame.Data(1, Unpooled.copiedBuffer("hello", StandardCharsets.US_ASCII)));
        Frame.Data echoed = peer.expect(Frame.Data.class, TIMEOUT);
        try {
            assertThat(echoed.payload().toString(StandardCharsets.US_ASCII)).isEqualTo("hello");
        } finally {
            echoed.release();
        }
        peer.send(Frame.Close.half(1));
        assertThat(peer.expect(Frame.Close.class, TIMEOUT)).isEqualTo(Frame.Close.half(1));

        cli.awaitOutput("closed");
        assertThat(cli.lines())
                .contains("12:01:07  203.0.113.9:51234  opened")
                .anySatisfy(line -> assertThat(line)
                        .matches("12:01:07  203\\.0\\.113\\.9:51234  closed   in 5 B  out 5 B  \\d+ms"));
        stop.request();
        assertThat(cli.exitCode(command)).isZero();
    }

    @Test
    void stoppingReturnsZeroAndReportsTheCloseAsTheLastEvent() throws Exception {
        Future<Integer> command = establish("tcp", "22", "--output", "json");
        cli.awaitOutput(NL);

        stop.request();

        assertThat(cli.exitCode(command)).isEqualTo(ExitCodes.USER_CLOSED);
        assertThat(stop.exitCode.toCompletableFuture().getNow(null)).isEqualTo(ExitCodes.USER_CLOSED);
        assertThat(stop.uninstalled).isTrue();
        assertThat(peer.expectClosed(TIMEOUT)).isEqualTo(WebSocketCloseStatus.NORMAL_CLOSURE);
        JsonNode closed = cli.jsonLines().getLast();
        assertThat(closed.get("type").asString()).isEqualTo("closed");
        assertThat(closed.get("reason").asString()).isEqualTo("USER_CLOSED");
        assertThat(closed.get("code").isNull()).isTrue();
    }

    @Test
    void aDroppedConnectionExitsFourWithAdvice() throws Exception {
        Future<Integer> command = establish("tcp", "22");
        cli.awaitOutput("Forwarding");

        peer.dropConnection();

        assertThat(cli.exitCode(command)).isEqualTo(ExitCodes.CONNECTION_FAILED);
        assertThat(cli.err())
                .startsWith("Error CONNECTION_LOST: ")
                .endsWith(" Run the command again; automatic reconnect arrives in a later release." + NL);
    }

    @Test
    void aFlowControlErrorFromTheServerExitsSixAndIsTheClosedEventsCode() throws Exception {
        Future<Integer> command = establish("tcp", "22", "--output", "json");
        cli.awaitOutput(NL);

        peer.send(Frame.Error.of(ErrorCode.FLOW_CONTROL_ERROR, "DATA on stream 1 exceeds its window."));

        assertThat(cli.exitCode(command)).isEqualTo(ExitCodes.PROTOCOL_MISMATCH);
        JsonNode closed = cli.jsonLines().getLast();
        assertThat(closed.get("type").asString()).isEqualTo("closed");
        assertThat(closed.get("reason").asString()).isEqualTo("SERVER_ERROR");
        assertThat(closed.get("code").asString()).isEqualTo("FLOW_CONTROL_ERROR");
    }

    // Sessions that never start.

    @Test
    void anInvalidAuthtokenExitsThreeWithAdvice() throws Exception {
        peer = ScriptedPeer.listen(0, ProtocolVersion.CURRENT);
        Future<Integer> command =
                cli.runAsync("tcp", "22", "--server", peer.uri().toString());
        peer.expect(Frame.Hello.class, TIMEOUT);
        peer.expect(Frame.Register.class, TIMEOUT);

        peer.send(Frame.Error.of(ErrorCode.AUTHTOKEN_INVALID, "The authtoken is not valid."));

        assertThat(cli.exitCode(command)).isEqualTo(ExitCodes.AUTHENTICATION_FAILED);
        assertThat(cli.err())
                .isEqualTo("Error AUTHTOKEN_INVALID: The authtoken is not valid."
                        + " Check the token; tokens are shown once when created." + NL);
        assertThat(cli.out()).isEmpty();
    }

    @Test
    void anUnavailablePortExitsFiveWithAdvice() throws Exception {
        peer = ScriptedPeer.listen(0, ProtocolVersion.CURRENT);
        Future<Integer> command =
                cli.runAsync("tcp", "22", "--server", peer.uri().toString(), "--remote-port", "21000");
        peer.expect(Frame.Hello.class, TIMEOUT);
        Frame.Register register = peer.expect(Frame.Register.class, TIMEOUT);
        assertThat(register.message().tunnels().getFirst().remotePort()).isEqualTo(21_000);

        peer.send(Frame.Error.of(ErrorCode.PORT_UNAVAILABLE, "Port 21000 is in use."));

        assertThat(cli.exitCode(command)).isEqualTo(ExitCodes.REGISTRATION_REFUSED);
        assertThat(cli.err()).contains("PORT_UNAVAILABLE").endsWith(" Try without --remote-port." + NL);
    }

    @Test
    void anUnsupportedVersionExitsSixNamingTheServersVersions() throws Exception {
        String body = versionRefusal("This server speaks jburrow.v1-draft.3.", "jburrow.v1-draft.3");

        try (RefusingServer server = RefusingServer.start(400, "application/json", body)) {
            int exitCode = cli.run("tcp", "22", "--server", server.uri().toString());

            assertThat(exitCode).isEqualTo(ExitCodes.PROTOCOL_MISMATCH);
            assertThat(cli.err())
                    .startsWith("Error UNSUPPORTED_PROTOCOL_VERSION: ")
                    .endsWith(" This server speaks jburrow.v1-draft.3; upgrade jburrow." + NL);
        }
    }

    /**
     * What a phase 1 CLI prints against the phase 2 server: the server's refusal of draft 1, word for word, through a
     * mapping that is unchanged since phase 1.
     */
    @Test
    void aDraftOneCliRefusedByTheDraftTwoServerIsToldToUpgrade() throws Exception {
        String body =
                versionRefusal("This server speaks jburrow.v1-draft.2. Upgrade the jburrow CLI.", "jburrow.v1-draft.2");

        try (RefusingServer server = RefusingServer.start(400, "application/json", body)) {
            int exitCode = cli.run("tcp", "22", "--server", server.uri().toString());

            assertThat(exitCode).isEqualTo(ExitCodes.PROTOCOL_MISMATCH);
            assertThat(cli.err())
                    .isEqualTo("Error UNSUPPORTED_PROTOCOL_VERSION: This server speaks jburrow.v1-draft.2."
                            + " Upgrade the jburrow CLI. This server speaks jburrow.v1-draft.2; upgrade jburrow." + NL);
            assertThat(cli.out()).isEmpty();
        }
    }

    @Test
    void aStartFailureIsTheFinalJsonEvent() throws Exception {
        int port;
        try (ServerSocket unused = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            port = unused.getLocalPort();
        }

        int exitCode = cli.run("tcp", "22", "--server", "ws://127.0.0.1:" + port + "/v1/connect", "--output", "json");

        assertThat(exitCode).isEqualTo(ExitCodes.CONNECTION_FAILED);
        JsonNode closed = cli.jsonLines().getLast();
        assertThat(closed.get("type").asString()).isEqualTo("closed");
        assertThat(closed.get("reason").asString()).isEqualTo("CONNECT_FAILED");
    }

    @Test
    void anUnexpectedFailureExitsOne() throws Exception {
        peer = ScriptedPeer.listen(0, ProtocolVersion.CURRENT);
        stop.failOnInstall = true;
        Future<Integer> command =
                cli.runAsync("tcp", "22", "--server", peer.uri().toString(), "--output", "json");
        peer.expect(Frame.Hello.class, TIMEOUT);
        peer.expect(Frame.Register.class, TIMEOUT);
        replyRegistered();

        assertThat(cli.exitCode(command)).isEqualTo(ExitCodes.INTERNAL_ERROR);
        assertThat(cli.jsonLines().getLast().get("reason").asString()).isEqualTo("INTERNAL_ERROR");
        assertThat(peer.expectClosed(TIMEOUT)).isEqualTo(WebSocketCloseStatus.NORMAL_CLOSURE);
    }

    // Usage errors.

    @Test
    void noAuthtokenIsAUsageError() {
        cli.environment().clear();

        int exitCode = cli.run("tcp", "22", "--server", "ws://127.0.0.1:7080/v1/connect");

        assertThat(exitCode).isEqualTo(ExitCodes.USAGE);
        assertThat(cli.err()).startsWith("No authtoken. Set JBURROW_AUTHTOKEN or pass --authtoken.");
        assertThat(cli.out()).isEmpty();
    }

    @Test
    void aUsageErrorIsTheFinalJsonEvent() throws Exception {
        cli.environment().clear();

        int exitCode = cli.run("tcp", "22", "--output", "json");

        assertThat(exitCode).isEqualTo(ExitCodes.USAGE);
        JsonNode closed = cli.jsonLines().getLast();
        assertThat(closed.get("reason").asString()).isEqualTo("USAGE_ERROR");
        assertThat(closed.get("message").asString()).startsWith("No authtoken.");
    }

    @Test
    void plainWebSocketToAnotherMachineIsAUsageError() {
        int exitCode = cli.run("tcp", "22", "--server", "ws://example.com/v1/connect");

        assertThat(exitCode).isEqualTo(ExitCodes.USAGE);
        assertThat(cli.err()).contains("ws:// sends the authtoken unencrypted");
    }

    @Test
    void aPortOutOfRangeIsAUsageError() {
        int exitCode = cli.run("tcp", "70000");

        assertThat(exitCode).isEqualTo(ExitCodes.USAGE);
        assertThat(cli.err()).contains("needs a port between 1 and 65535");
    }

    @Test
    void anUnknownOutputFormatIsAUsageError() {
        assertThat(cli.run("tcp", "22", "--output", "xml")).isEqualTo(ExitCodes.USAGE);
    }

    // Helpers.

    /** Starts the command and answers its handshake; returns once it has printed where the tunnel is. */
    private Future<Integer> establish(String... args) throws Exception {
        peer = ScriptedPeer.listen(0, ProtocolVersion.CURRENT);
        List<String> withServer = new ArrayList<>(Arrays.asList(args));
        withServer.addAll(List.of("--server", peer.uri().toString()));
        Future<Integer> command = cli.runAsync(withServer.toArray(String[]::new));
        peer.expect(Frame.Hello.class, TIMEOUT);
        peer.expect(Frame.Register.class, TIMEOUT);
        replyRegistered();
        stop.awaitInstalled();
        return command;
    }

    private void replyRegistered() {
        peer.send(new Frame.ServerHello(new ServerHello(
                SESSION_ID,
                new ServerInfo("jburrow-server", "0.2.0"),
                List.of(),
                LIMITS,
                ScriptedPeer.SERVER_WINDOWS)));
        peer.send(new Frame.Registered(new Registered(
                List.of(RegisteredTunnel.of("default", TUNNEL_ID, TunnelKind.TCP, "localhost", 21_000)))));
    }

    private static String versionRefusal(String message, String... supported) {
        return new String(
                ControlJson.create()
                        .writeRefusal(UpgradeRefusal.unsupportedVersion(
                                message, List.of(supported), Instant.now(), ScriptedPeer.CONNECT_PATH)),
                StandardCharsets.UTF_8);
    }

    private void startEchoService() throws IOException {
        local = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        cli.threads().submit(() -> {
            try (Socket socket = local.accept();
                    InputStream in = socket.getInputStream();
                    OutputStream echo = socket.getOutputStream()) {
                in.transferTo(echo);
                socket.shutdownOutput();
            }
            return null;
        });
    }
}
