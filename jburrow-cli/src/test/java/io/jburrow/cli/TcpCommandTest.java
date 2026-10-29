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
import java.io.PrintWriter;
import java.io.StringWriter;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The real command, through {@link JBurrowCli}'s command line, against {@link ScriptedPeer} playing the server over
 * {@code ws://127.0.0.1}. Stopping is the command's own close path, not a signal.
 */
class TcpCommandTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final String TOKEN = "jb_" + "t".repeat(43);
    private static final UUID SESSION_ID = UUID.fromString("0b8e5c1e-7c55-4c46-9a3e-2f1d0c9e4b7a");
    private static final UUID TUNNEL_ID = UUID.fromString("5d0f4f0e-31a6-4a39-8d51-0c0f7f1f6c23");
    private static final SessionLimits LIMITS = new SessionLimits(FrameLimits.MAX_DATA_PAYLOAD, 1, 1, 15_000, 45_000);
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-25T12:01:07Z"), ZoneOffset.UTC);
    private static final String NL = System.lineSeparator();
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final StringWriter out = new StringWriter();
    private final StringWriter err = new StringWriter();
    private final Map<String, String> environment = new HashMap<>(Map.of(TcpCommand.AUTHTOKEN_VARIABLE, TOKEN));
    private final ManualStop stop = new ManualStop();
    private final ExecutorService threads = Executors.newVirtualThreadPerTaskExecutor();
    private ScriptedPeer peer;
    private ServerSocket local;

    @AfterEach
    void stop() throws IOException {
        assertThat(out.toString()).doesNotContain(TOKEN);
        assertThat(err.toString()).doesNotContain(TOKEN);
        if (peer != null) {
            peer.close();
        }
        if (local != null) {
            local.close();
        }
        threads.shutdownNow();
    }

    // Established sessions.

    @Test
    void anEstablishedTunnelPrintsTheVersionAndWhereItForwards() throws Exception {
        Future<Integer> command = establish("tcp", "22");

        awaitOutput(NL + NL);
        assertThat(lines())
                .containsExactly(
                        "jburrow " + new BuildInfo().version() + ", protocol " + ProtocolVersion.CURRENT,
                        "Forwarding  tcp://localhost:21000 -> localhost:22",
                        "");
        assertThat(lines().getFirst()).endsWith(", protocol jburrow.v1-draft.2");

        stop.request();
        assertThat(exitCode(command)).isZero();
        assertThat(err.toString()).isEmpty();
    }

    @Test
    void jsonOutputReportsTheRegisteredTunnel() throws Exception {
        Future<Integer> command = establish("tcp", "22", "--output", "json");

        awaitOutput(NL);
        JsonNode registered = jsonLines().getFirst();
        assertThat(registered.get("type").asString()).isEqualTo("registered");
        assertThat(registered.get("sessionId").asString()).isEqualTo(SESSION_ID.toString());
        JsonNode tunnel = registered.get("tunnels").get(0);
        assertThat(tunnel.get("url").asString()).isEqualTo("tcp://localhost:21000");
        assertThat(tunnel.get("local").asString()).isEqualTo("localhost:22");

        stop.request();
        assertThat(exitCode(command)).isZero();
    }

    @Test
    void theTokenCanComeFromTheCommandLine() throws Exception {
        environment.clear();
        Future<Integer> command = establish("tcp", "22", "--authtoken", TOKEN);

        awaitOutput("Forwarding");
        stop.request();

        assertThat(exitCode(command)).isZero();
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

        awaitOutput("closed");
        assertThat(lines())
                .contains("12:01:07  203.0.113.9:51234  opened")
                .anySatisfy(line -> assertThat(line)
                        .matches("12:01:07  203\\.0\\.113\\.9:51234  closed   in 5 B  out 5 B  \\d+ms"));
        stop.request();
        assertThat(exitCode(command)).isZero();
    }

    @Test
    void stoppingReturnsZeroAndReportsTheCloseAsTheLastEvent() throws Exception {
        Future<Integer> command = establish("tcp", "22", "--output", "json");
        awaitOutput(NL);

        stop.request();

        assertThat(exitCode(command)).isEqualTo(ExitCodes.USER_CLOSED);
        assertThat(stop.exitCode.toCompletableFuture().getNow(null)).isEqualTo(ExitCodes.USER_CLOSED);
        assertThat(stop.uninstalled).isTrue();
        assertThat(peer.expectClosed(TIMEOUT)).isEqualTo(WebSocketCloseStatus.NORMAL_CLOSURE);
        JsonNode closed = jsonLines().getLast();
        assertThat(closed.get("type").asString()).isEqualTo("closed");
        assertThat(closed.get("reason").asString()).isEqualTo("USER_CLOSED");
        assertThat(closed.get("code").isNull()).isTrue();
    }

    @Test
    void aDroppedConnectionExitsFourWithAdvice() throws Exception {
        Future<Integer> command = establish("tcp", "22");
        awaitOutput("Forwarding");

        peer.dropConnection();

        assertThat(exitCode(command)).isEqualTo(ExitCodes.CONNECTION_FAILED);
        assertThat(err.toString())
                .startsWith("Error CONNECTION_LOST: ")
                .endsWith(" Run the command again; automatic reconnect arrives in a later release." + NL);
    }

    @Test
    void aFlowControlErrorFromTheServerExitsSixAndIsTheClosedEventsCode() throws Exception {
        Future<Integer> command = establish("tcp", "22", "--output", "json");
        awaitOutput(NL);

        peer.send(Frame.Error.of(ErrorCode.FLOW_CONTROL_ERROR, "DATA on stream 1 exceeds its window."));

        assertThat(exitCode(command)).isEqualTo(ExitCodes.PROTOCOL_MISMATCH);
        JsonNode closed = jsonLines().getLast();
        assertThat(closed.get("type").asString()).isEqualTo("closed");
        assertThat(closed.get("reason").asString()).isEqualTo("SERVER_ERROR");
        assertThat(closed.get("code").asString()).isEqualTo("FLOW_CONTROL_ERROR");
    }

    // Sessions that never start.

    @Test
    void anInvalidAuthtokenExitsThreeWithAdvice() throws Exception {
        peer = ScriptedPeer.listen(0, ProtocolVersion.CURRENT);
        Future<Integer> command = runAsync("tcp", "22", "--server", peer.uri().toString());
        peer.expect(Frame.Hello.class, TIMEOUT);
        peer.expect(Frame.Register.class, TIMEOUT);

        peer.send(Frame.Error.of(ErrorCode.AUTHTOKEN_INVALID, "The authtoken is not valid."));

        assertThat(exitCode(command)).isEqualTo(ExitCodes.AUTHENTICATION_FAILED);
        assertThat(err.toString())
                .isEqualTo("Error AUTHTOKEN_INVALID: The authtoken is not valid."
                        + " Check the token; tokens are shown once when created." + NL);
        assertThat(out.toString()).isEmpty();
    }

    @Test
    void anUnavailablePortExitsFiveWithAdvice() throws Exception {
        peer = ScriptedPeer.listen(0, ProtocolVersion.CURRENT);
        Future<Integer> command = runAsync("tcp", "22", "--server", peer.uri().toString(), "--remote-port", "21000");
        peer.expect(Frame.Hello.class, TIMEOUT);
        Frame.Register register = peer.expect(Frame.Register.class, TIMEOUT);
        assertThat(register.message().tunnels().getFirst().remotePort()).isEqualTo(21_000);

        peer.send(Frame.Error.of(ErrorCode.PORT_UNAVAILABLE, "Port 21000 is in use."));

        assertThat(exitCode(command)).isEqualTo(ExitCodes.REGISTRATION_REFUSED);
        assertThat(err.toString()).contains("PORT_UNAVAILABLE").endsWith(" Try without --remote-port." + NL);
    }

    @Test
    void anUnsupportedVersionExitsSixNamingTheServersVersions() throws Exception {
        String body = versionRefusal("This server speaks jburrow.v1-draft.3.", "jburrow.v1-draft.3");

        try (RefusingServer server = RefusingServer.start(400, "application/json", body)) {
            int exitCode = run("tcp", "22", "--server", server.uri().toString());

            assertThat(exitCode).isEqualTo(ExitCodes.PROTOCOL_MISMATCH);
            assertThat(err.toString())
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
            int exitCode = run("tcp", "22", "--server", server.uri().toString());

            assertThat(exitCode).isEqualTo(ExitCodes.PROTOCOL_MISMATCH);
            assertThat(err.toString())
                    .isEqualTo("Error UNSUPPORTED_PROTOCOL_VERSION: This server speaks jburrow.v1-draft.2."
                            + " Upgrade the jburrow CLI. This server speaks jburrow.v1-draft.2; upgrade jburrow." + NL);
            assertThat(out.toString()).isEmpty();
        }
    }

    @Test
    void aStartFailureIsTheFinalJsonEvent() throws Exception {
        int port;
        try (ServerSocket unused = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            port = unused.getLocalPort();
        }

        int exitCode = run("tcp", "22", "--server", "ws://127.0.0.1:" + port + "/v1/connect", "--output", "json");

        assertThat(exitCode).isEqualTo(ExitCodes.CONNECTION_FAILED);
        JsonNode closed = jsonLines().getLast();
        assertThat(closed.get("type").asString()).isEqualTo("closed");
        assertThat(closed.get("reason").asString()).isEqualTo("CONNECT_FAILED");
    }

    @Test
    void anUnexpectedFailureExitsOne() throws Exception {
        peer = ScriptedPeer.listen(0, ProtocolVersion.CURRENT);
        stop.failOnInstall = true;
        Future<Integer> command = runAsync("tcp", "22", "--server", peer.uri().toString(), "--output", "json");
        peer.expect(Frame.Hello.class, TIMEOUT);
        peer.expect(Frame.Register.class, TIMEOUT);
        replyRegistered();

        assertThat(exitCode(command)).isEqualTo(ExitCodes.INTERNAL_ERROR);
        assertThat(jsonLines().getLast().get("reason").asString()).isEqualTo("INTERNAL_ERROR");
        assertThat(peer.expectClosed(TIMEOUT)).isEqualTo(WebSocketCloseStatus.NORMAL_CLOSURE);
    }

    // Usage errors.

    @Test
    void noAuthtokenIsAUsageError() {
        environment.clear();

        int exitCode = run("tcp", "22", "--server", "ws://127.0.0.1:7080/v1/connect");

        assertThat(exitCode).isEqualTo(ExitCodes.USAGE);
        assertThat(err.toString()).startsWith("No authtoken. Set JBURROW_AUTHTOKEN or pass --authtoken.");
        assertThat(out.toString()).isEmpty();
    }

    @Test
    void aUsageErrorIsTheFinalJsonEvent() throws Exception {
        environment.clear();

        int exitCode = run("tcp", "22", "--output", "json");

        assertThat(exitCode).isEqualTo(ExitCodes.USAGE);
        JsonNode closed = jsonLines().getLast();
        assertThat(closed.get("reason").asString()).isEqualTo("USAGE_ERROR");
        assertThat(closed.get("message").asString()).startsWith("No authtoken.");
    }

    @Test
    void plainWebSocketToAnotherMachineIsAUsageError() {
        int exitCode = run("tcp", "22", "--server", "ws://example.com/v1/connect");

        assertThat(exitCode).isEqualTo(ExitCodes.USAGE);
        assertThat(err.toString()).contains("ws:// sends the authtoken unencrypted");
    }

    @Test
    void aPortOutOfRangeIsAUsageError() {
        int exitCode = run("tcp", "70000");

        assertThat(exitCode).isEqualTo(ExitCodes.USAGE);
        assertThat(err.toString()).contains("needs a port between 1 and 65535");
    }

    @Test
    void anUnknownOutputFormatIsAUsageError() {
        assertThat(run("tcp", "22", "--output", "xml")).isEqualTo(ExitCodes.USAGE);
    }

    // Helpers.

    /** Starts the command and answers its handshake; returns once it has printed where the tunnel is. */
    private Future<Integer> establish(String... args) throws Exception {
        peer = ScriptedPeer.listen(0, ProtocolVersion.CURRENT);
        List<String> withServer = new ArrayList<>(Arrays.asList(args));
        withServer.addAll(List.of("--server", peer.uri().toString()));
        Future<Integer> command = runAsync(withServer.toArray(String[]::new));
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

    private Future<Integer> runAsync(String... args) {
        return threads.submit(() -> run(args));
    }

    private int run(String... args) {
        CommandLine.IFactory factory = new CommandLine.IFactory() {
            @Override
            public <K> K create(Class<K> type) throws Exception {
                if (type == TcpCommand.class) {
                    return type.cast(new TcpCommand(environment::get, stop, CLOCK));
                }
                return CommandLine.defaultFactory().create(type);
            }
        };
        CommandLine cli = JBurrowCli.commandLine(factory);
        cli.setOut(new PrintWriter(out, true));
        cli.setErr(new PrintWriter(err, true));
        return cli.execute(args);
    }

    private static int exitCode(Future<Integer> command) throws Exception {
        return command.get(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
    }

    private void startEchoService() throws IOException {
        local = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        threads.submit(() -> {
            try (Socket socket = local.accept();
                    InputStream in = socket.getInputStream();
                    OutputStream echo = socket.getOutputStream()) {
                in.transferTo(echo);
                socket.shutdownOutput();
            }
            return null;
        });
    }

    private List<String> lines() {
        return out.toString().lines().toList();
    }

    private List<JsonNode> jsonLines() {
        return lines().stream().map(JSON::readTree).toList();
    }

    private void awaitOutput(String text) throws InterruptedException {
        long deadline = System.nanoTime() + TIMEOUT.toNanos();
        while (!out.toString().contains(text)) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("stdout didn't contain '" + text + "' within " + TIMEOUT + ":\n" + out);
            }
            Thread.sleep(5);
        }
    }

    /** The command's stop path, run on request instead of by a signal. */
    private static final class ManualStop implements StopSignal {

        private final CountDownLatch installed = new CountDownLatch(1);
        private volatile Runnable stop;
        private volatile CompletionStage<Integer> exitCode;
        private volatile boolean uninstalled;
        private volatile boolean failOnInstall;

        @Override
        public void install(Runnable stop, CompletionStage<Integer> exitCode) {
            if (failOnInstall) {
                throw new IllegalStateException("The stop signal can't be installed");
            }
            this.stop = stop;
            this.exitCode = exitCode;
            installed.countDown();
        }

        @Override
        public void uninstall() {
            uninstalled = true;
        }

        void awaitInstalled() throws InterruptedException {
            assertThat(installed.await(TIMEOUT.toSeconds(), TimeUnit.SECONDS))
                    .as("the command installed its stop signal")
                    .isTrue();
        }

        void request() {
            stop.run();
        }
    }
}
