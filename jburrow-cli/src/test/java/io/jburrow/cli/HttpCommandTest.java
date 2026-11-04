package io.jburrow.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import io.jburrow.protocol.adapter.netty.Frame;
import io.jburrow.protocol.control.BodyFraming;
import io.jburrow.protocol.control.GoAway;
import io.jburrow.protocol.control.HeaderFields;
import io.jburrow.protocol.control.HttpRequestHead;
import io.jburrow.protocol.control.Open;
import io.jburrow.protocol.control.Registered;
import io.jburrow.protocol.control.RegisteredTunnel;
import io.jburrow.protocol.control.RemoteAddress;
import io.jburrow.protocol.control.ResumeToken;
import io.jburrow.protocol.control.ServerHello;
import io.jburrow.protocol.control.ServerInfo;
import io.jburrow.protocol.control.SessionLimits;
import io.jburrow.protocol.control.TunnelKind;
import io.jburrow.protocol.control.TunnelRequest;
import io.jburrow.protocol.testsupport.ScriptedPeer;
import io.jburrow.protocol.wire.ErrorCode;
import io.jburrow.protocol.wire.FrameLimits;
import io.jburrow.protocol.wire.ProtocolVersion;
import io.netty.handler.codec.http.websocketx.WebSocketCloseStatus;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * The real command, through {@link JBurrowCli}'s command line, against {@link ScriptedPeer} playing the server over
 * {@code ws://127.0.0.1} and the JDK's HTTP server as the local service. Stopping is the command's own close path, not
 * a signal.
 */
class HttpCommandTest {

    private static final Duration TIMEOUT = CommandHarness.TIMEOUT;
    private static final String TOKEN = "jb_" + "t".repeat(43);
    private static final UUID SESSION_ID = UUID.fromString("0b8e5c1e-7c55-4c46-9a3e-2f1d0c9e4b7a");
    private static final UUID TUNNEL_ID = UUID.fromString("5d0f4f0e-31a6-4a39-8d51-0c0f7f1f6c23");
    private static final SessionLimits LIMITS = new SessionLimits(FrameLimits.MAX_DATA_PAYLOAD, 1, 1, 15_000, 45_000);
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-25T12:01:07Z"), ZoneOffset.UTC);
    private static final String PUBLIC_HOST = "quiet-otter-4f2a.localhost";
    private static final RemoteAddress VISITOR = new RemoteAddress("203.0.113.9", 51_234);
    private static final String NL = System.lineSeparator();
    private static final UUID SECOND_SESSION_ID = UUID.fromString("1c9f6d2f-8d66-4d57-ab4f-3a2e1dafb58b");
    private static final ResumeToken FIRST_TOKEN = new ResumeToken("jbr_AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8");
    private static final ResumeToken SECOND_TOKEN = new ResumeToken("jbr_ICEiIyQlJicoKSorLC0uLzAxMjM0NTY3ODk6Ozw9Pj8");

    private final CommandHarness cli = new CommandHarness(CLOCK, TOKEN);
    private final Queue<String> localHosts = new ConcurrentLinkedQueue<>();
    private ScriptedPeer peer;
    private HttpServer local;

    @AfterEach
    void stop() {
        assertThat(cli.out()).doesNotContain(TOKEN);
        assertThat(cli.err()).doesNotContain(TOKEN);
        if (peer != null) {
            peer.close();
        }
        if (local != null) {
            local.stop(0);
        }
        cli.close();
    }

    // Established tunnels.

    @Test
    void anEstablishedTunnelPrintsItsUrlAndTheLocalService() throws Exception {
        Future<Integer> command = establish("http", "3000");

        cli.awaitOutput(NL + NL);
        assertThat(cli.lines())
                .containsExactly(
                        "jburrow " + new BuildInfo().version() + ", protocol " + ProtocolVersion.CURRENT,
                        "Forwarding  http://" + PUBLIC_HOST + ":7080 -> http://localhost:3000",
                        "");

        cli.stop().request();
        assertThat(cli.exitCode(command)).isEqualTo(ExitCodes.USER_CLOSED);
        assertThat(cli.err()).isEmpty();
    }

    @Test
    void eachRequestIsALineWhenItsResponseEnds() throws Exception {
        startLocalService();
        Future<Integer> command =
                establish("http", String.valueOf(local.getAddress().getPort()));

        exchange(1, "/hello?name=x");

        cli.awaitOutput("200");
        assertThat(cli.lines().getLast()).matches("12:01:07  GET  /hello\\?name=x          200       6 B +\\d+ ms");
        assertThat(cli.lines()).noneMatch(line -> line.contains("opened") || line.contains("203.0.113.9"));
        cli.stop().request();
        assertThat(cli.exitCode(command)).isZero();
    }

    @Test
    void aRequestToAServiceThatIsntRunningPrintsTheReason() throws Exception {
        int unused;
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            unused = socket.getLocalPort();
        }
        Future<Integer> command = establish("http", "127.0.0.1:" + unused);

        peer.send(new Frame.Open(1, new Open(TUNNEL_ID, TunnelKind.HTTP, VISITOR, get("/missing"))));
        peer.send(Frame.Close.half(1));
        assertThat(peer.expect(Frame.Close.class, TIMEOUT).reset()).contains(ErrorCode.LOCAL_SERVICE_UNREACHABLE);

        cli.awaitOutput("unreachable");
        assertThat(cli.lines().getLast())
                .matches("12:01:07  GET  /missing                 -         - +\\d+ ms  local service unreachable");
        cli.stop().request();
        assertThat(cli.exitCode(command)).isZero();
    }

    @Test
    void jsonOutputReportsTheTunnelEachRequestAndTheClose() throws Exception {
        startLocalService();
        Future<Integer> command =
                establish("http", String.valueOf(local.getAddress().getPort()), "--output", "json");

        exchange(1, "/hello");
        cli.awaitOutput("\"type\":\"request\"");
        cli.stop().request();
        assertThat(cli.exitCode(command)).isZero();

        List<JsonNode> events = cli.jsonLines();
        JsonNode tunnel = events.getFirst().get("tunnels").get(0);
        assertThat(events.getFirst().get("type").asString()).isEqualTo("registered");
        assertThat(tunnel.get("url").asString()).isEqualTo("http://" + PUBLIC_HOST + ":7080");
        assertThat(tunnel.get("publicHost").asString()).isEqualTo(PUBLIC_HOST);
        JsonNode request = events.stream()
                .filter(event -> event.get("type").asString().equals("request"))
                .findFirst()
                .orElseThrow();
        assertThat(request.get("streamId").asInt()).isEqualTo(1);
        assertThat(request.get("method").asString()).isEqualTo("GET");
        assertThat(request.get("target").asString()).isEqualTo("/hello");
        assertThat(request.get("status").asInt()).isEqualTo(200);
        assertThat(request.get("reset").isNull()).isTrue();
        assertThat(request.get("requestBytes").asLong()).isZero();
        assertThat(request.get("responseBytes").asLong()).isEqualTo(6);
        assertThat(events.getLast().get("type").asString()).isEqualTo("closed");
        assertThat(events.getLast().get("reason").asString()).isEqualTo("USER_CLOSED");
    }

    @Test
    void theSubdomainIsLowerCasedBeforeItIsSent() throws Exception {
        Future<Integer> command = establish(
                List.of(new TunnelRequest("default", TunnelKind.HTTP, "myapp", null)),
                "http",
                "3000",
                "--subdomain",
                "MyApp");

        cli.stop().request();
        assertThat(cli.exitCode(command)).isZero();
    }

    @Test
    void rewritingTheHostHeaderSendsTheLocalServiceItsOwnName() throws Exception {
        startLocalService();
        int port = local.getAddress().getPort();
        Future<Integer> command = establish("http", "127.0.0.1:" + port, "--host-header", "rewrite");

        exchange(1, "/hello");

        assertThat(localHosts).containsExactly("127.0.0.1:" + port);
        cli.stop().request();
        assertThat(cli.exitCode(command)).isZero();
    }

    // Sessions that never start.

    @Test
    void aSubdomainInUseExitsFiveNamingIt() throws Exception {
        int exitCode = refusedRegistration(ErrorCode.SUBDOMAIN_UNAVAILABLE, "The subdomain is in use.");

        assertThat(exitCode).isEqualTo(ExitCodes.REGISTRATION_REFUSED);
        assertThat(cli.err())
                .isEqualTo("Error SUBDOMAIN_UNAVAILABLE: The subdomain myapp is in use."
                        + " Try another, or leave out --subdomain." + NL);
        assertThat(cli.out()).isEmpty();
    }

    @Test
    void aForbiddenSubdomainExitsFiveNamingIt() throws Exception {
        int exitCode = refusedRegistration(ErrorCode.SUBDOMAIN_FORBIDDEN, "The subdomain is reserved.");

        assertThat(exitCode).isEqualTo(ExitCodes.REGISTRATION_REFUSED);
        assertThat(cli.err())
                .isEqualTo("Error SUBDOMAIN_FORBIDDEN: The subdomain myapp isn't allowed on this server." + NL);
    }

    @Test
    void aServerWithoutHttpTunnelsExitsSixWithoutRegistering() throws Exception {
        peer = ScriptedPeer.listen(0, ProtocolVersion.CURRENT);
        Future<Integer> command =
                cli.runAsync("http", "3000", "--server", peer.uri().toString(), "--output", "json");
        assertThat(peer.expect(Frame.Hello.class, TIMEOUT).message().capabilities())
                .containsExactlyInAnyOrder("http", "resume");

        peer.send(serverHello(List.of()));

        assertThat(cli.exitCode(command)).isEqualTo(ExitCodes.PROTOCOL_MISMATCH);
        assertThat(peer.expectClosed(TIMEOUT)).isEqualTo(WebSocketCloseStatus.NORMAL_CLOSURE);
        JsonNode closed = cli.jsonLines().getLast();
        assertThat(closed.get("type").asString()).isEqualTo("closed");
        assertThat(closed.get("reason").asString()).isEqualTo("UNSUPPORTED_BY_SERVER");
        assertThat(closed.get("message").asString()).isEqualTo("This server doesn't support HTTP tunnels.");
    }

    @Test
    void aServerWithoutHttpTunnelsSaysWhatToDo() throws Exception {
        peer = ScriptedPeer.listen(0, ProtocolVersion.CURRENT);
        Future<Integer> command =
                cli.runAsync("http", "3000", "--server", peer.uri().toString());
        peer.expect(Frame.Hello.class, TIMEOUT);

        peer.send(serverHello(List.of()));

        assertThat(cli.exitCode(command)).isEqualTo(ExitCodes.PROTOCOL_MISMATCH);
        assertThat(cli.err())
                .isEqualTo("Error UNSUPPORTED_BY_SERVER: This server doesn't support HTTP tunnels."
                        + " Upgrade the server, or use jburrow tcp." + NL);
    }

    // Usage errors.

    @Test
    void aSubdomainBreakingTheNameRuleIsAUsageErrorBeforeConnecting() {
        int exitCode = cli.run("http", "3000", "--subdomain", "-bad", "--server", "ws://127.0.0.1:1/v1/connect");

        assertThat(exitCode).isEqualTo(ExitCodes.USAGE);
        assertThat(cli.err())
                .startsWith("'-bad' is not a valid subdomain: use 3 to 63 letters, digits and hyphens, without a"
                        + " leading, trailing or doubled hyphen.");
    }

    @Test
    void aSubdomainUsageErrorIsTheFinalJsonEvent() {
        int exitCode = cli.run("http", "3000", "--subdomain", "a_b", "--output", "json");

        assertThat(exitCode).isEqualTo(ExitCodes.USAGE);
        JsonNode closed = cli.jsonLines().getLast();
        assertThat(closed.get("reason").asString()).isEqualTo("USAGE_ERROR");
        assertThat(closed.get("message").asString()).startsWith("'a_b' is not a valid subdomain");
    }

    @Test
    void aHostHeaderOtherThanRewriteIsAUsageError() {
        assertThat(cli.run("http", "3000", "--host-header", "keep")).isEqualTo(ExitCodes.USAGE);
        assertThat(cli.run("http", "3000", "--host-header", "example.com")).isEqualTo(ExitCodes.USAGE);
    }

    @Test
    void noAuthtokenIsAUsageError() {
        cli.environment().clear();

        int exitCode = cli.run("http", "3000", "--server", "ws://127.0.0.1:7080/v1/connect");

        assertThat(exitCode).isEqualTo(ExitCodes.USAGE);
        assertThat(cli.err()).startsWith("No authtoken. Set JBURROW_AUTHTOKEN or pass --authtoken.");
    }

    @Test
    void aRemotePortIsNotAnHttpOption() {
        assertThat(cli.run("http", "3000", "--remote-port", "21000")).isEqualTo(ExitCodes.USAGE);
    }

    // Reconnecting.

    @Test
    void aServerRestartIsADisconnectedAndAReconnectedEventWithTheSameUrlAndNoToken() throws Exception {
        peer = ScriptedPeer.listen(0, ProtocolVersion.CURRENT);
        Future<Integer> command =
                cli.runAsync("http", "3000", "--server", peer.uri().toString(), "--output", "json");
        assertThat(peer.expect(Frame.Hello.class, TIMEOUT).message().capabilities())
                .containsExactlyInAnyOrder("http", "resume");
        peer.send(serverHello(SESSION_ID, List.of("http", "resume")));
        peer.expect(Frame.Register.class, TIMEOUT);
        peer.send(new Frame.Registered(new Registered(List.of(otter()), FIRST_TOKEN)));
        cli.stop().awaitInstalled();
        ScriptedPeer first = peer;
        first.stopListening();
        peer = ScriptedPeer.listen(first.port(), ProtocolVersion.CURRENT);

        first.send(new Frame.GoAway(new GoAway(300, 5_000)));
        first.send(Frame.Error.of(ErrorCode.SERVER_SHUTTING_DOWN, "The server is shutting down."));
        first.close();
        peer.expect(Frame.Hello.class, TIMEOUT);
        peer.send(serverHello(SECOND_SESSION_ID, List.of("http", "resume")));
        Frame.Register register = peer.expect(Frame.Register.class, TIMEOUT);
        assertThat(register.message().resume()).isEqualTo(FIRST_TOKEN);
        assertThat(register.message().tunnels().getFirst().subdomain()).isEqualTo("quiet-otter-4f2a");
        peer.send(new Frame.Registered(new Registered(List.of(otter()), SECOND_TOKEN)));

        cli.awaitOutput("\"type\":\"reconnected\"");
        assertThat(command).isNotDone();
        cli.stop().request();
        assertThat(cli.exitCode(command)).isEqualTo(ExitCodes.USER_CLOSED);
        List<JsonNode> events = cli.jsonLines();
        assertThat(events)
                .extracting(event -> event.get("type").asString())
                .containsExactly("registered", "disconnected", "reconnected", "closed");
        JsonNode disconnected = events.get(1);
        assertThat(disconnected.get("reason").asString()).isEqualTo("SERVER_ERROR");
        assertThat(disconnected.get("code").asString()).isEqualTo("SERVER_SHUTTING_DOWN");
        assertThat(disconnected.get("retryInMs").asLong()).isEqualTo(300);
        JsonNode reconnected = events.get(2);
        assertThat(reconnected.get("sessionId").asString()).isEqualTo(SECOND_SESSION_ID.toString());
        assertThat(reconnected.get("tunnels").get(0).get("url").asString())
                .isEqualTo("http://" + PUBLIC_HOST + ":7080");
        assertThat(reconnected.get("sameUrls").asBoolean()).isTrue();
        assertThat(events.getLast().get("reason").asString()).isEqualTo("USER_CLOSED");
        for (ResumeToken token : List.of(FIRST_TOKEN, SECOND_TOKEN)) {
            assertThat(cli.out()).doesNotContain(token.value());
            assertThat(cli.err()).doesNotContain(token.value());
        }
    }

    @Test
    void withNoReconnectAServerRestartExitsFour() throws Exception {
        Future<Integer> command = establish("http", "3000", "--no-reconnect");
        cli.awaitOutput("Forwarding");

        peer.send(Frame.Error.of(ErrorCode.SERVER_SHUTTING_DOWN, "The server is shutting down."));

        assertThat(cli.exitCode(command)).isEqualTo(ExitCodes.CONNECTION_FAILED);
        assertThat(cli.out()).doesNotContain("reconnect");
        assertThat(cli.err()).startsWith("Error SERVER_SHUTTING_DOWN: The server is shutting down.");
    }

    // Helpers.

    private Future<Integer> establish(String... args) throws Exception {
        return establish(List.of(new TunnelRequest("default", TunnelKind.HTTP, null, null)), args);
    }

    /** Starts the command and answers its handshake, expecting {@code tunnels} in its {@code REGISTER}. */
    private Future<Integer> establish(List<TunnelRequest> tunnels, String... args) throws Exception {
        peer = ScriptedPeer.listen(0, ProtocolVersion.CURRENT);
        List<String> withServer = new ArrayList<>(Arrays.asList(args));
        withServer.addAll(List.of("--server", peer.uri().toString()));
        Future<Integer> command = cli.runAsync(withServer.toArray(String[]::new));
        peer.expect(Frame.Hello.class, TIMEOUT);
        peer.send(serverHello(List.of("http")));
        assertThat(peer.expect(Frame.Register.class, TIMEOUT).message().tunnels())
                .isEqualTo(tunnels);
        String requested = tunnels.getFirst().subdomain();
        String host = requested == null ? PUBLIC_HOST : requested + ".localhost";
        peer.send(new Frame.Registered(
                new Registered(List.of(RegisteredTunnel.http("default", TUNNEL_ID, "http", host, 7080)))));
        peer.ignoreWindowUpdates();
        cli.stop().awaitInstalled();
        return command;
    }

    private int refusedRegistration(ErrorCode code, String message) throws Exception {
        peer = ScriptedPeer.listen(0, ProtocolVersion.CURRENT);
        Future<Integer> command = cli.runAsync(
                "http", "3000", "--subdomain", "myapp", "--server", peer.uri().toString());
        peer.expect(Frame.Hello.class, TIMEOUT);
        peer.send(serverHello(List.of("http")));
        peer.expect(Frame.Register.class, TIMEOUT);

        peer.send(Frame.Error.of(code, message));

        return cli.exitCode(command);
    }

    private static Frame.ServerHello serverHello(List<String> capabilities) {
        return serverHello(SESSION_ID, capabilities);
    }

    private static Frame.ServerHello serverHello(UUID sessionId, List<String> capabilities) {
        return new Frame.ServerHello(new ServerHello(
                sessionId,
                new ServerInfo("jburrow-server", "0.3.0"),
                capabilities,
                LIMITS,
                ScriptedPeer.SERVER_WINDOWS));
    }

    private static RegisteredTunnel otter() {
        return RegisteredTunnel.http("default", TUNNEL_ID, "http", PUBLIC_HOST, 7080);
    }

    /** One {@code GET} through the tunnel, read to its end. */
    private void exchange(int streamId, String target) {
        peer.send(new Frame.Open(streamId, new Open(TUNNEL_ID, TunnelKind.HTTP, VISITOR, get(target))));
        peer.send(Frame.Close.half(streamId));
        Frame frame;
        do {
            frame = peer.expect(Frame.class, TIMEOUT);
            frame.release();
        } while (!(frame instanceof Frame.Close close && close.streamId() == streamId));
        assertThat(((Frame.Close) frame).reset()).isEmpty();
    }

    private static HttpRequestHead get(String target) {
        return new HttpRequestHead("GET", target, HeaderFields.of("host", PUBLIC_HOST), BodyFraming.EMPTY);
    }

    /** Answers every request with {@code hello\n}, and records the {@code host} it was sent. */
    private void startLocalService() throws IOException {
        local = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 50);
        local.createContext("/", exchange -> {
            localHosts.add(exchange.getRequestHeaders().getFirst("host"));
            byte[] body = "hello\n".getBytes(StandardCharsets.US_ASCII);
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        local.start();
    }
}
