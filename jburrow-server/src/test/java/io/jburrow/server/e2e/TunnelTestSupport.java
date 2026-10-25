package io.jburrow.server.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import io.jburrow.client.JBurrowClient;
import io.jburrow.client.TunnelHandle;
import io.jburrow.client.TunnelStartException;
import io.jburrow.client.tunnel.domain.LocalTarget;
import io.jburrow.client.tunnel.domain.TunnelClosed;
import io.jburrow.client.tunnel.domain.TunnelSpec;
import io.jburrow.protocol.adapter.netty.Frame;
import io.jburrow.protocol.control.ClientInfo;
import io.jburrow.protocol.control.Hello;
import io.jburrow.protocol.control.Register;
import io.jburrow.protocol.control.RegisteredTunnel;
import io.jburrow.protocol.control.TunnelKind;
import io.jburrow.protocol.control.TunnelRequest;
import io.jburrow.protocol.testsupport.ScriptedPeer;
import io.jburrow.protocol.wire.ProtocolVersion;
import io.jburrow.server.core.tunnel.adapter.netty.edge.EdgeServer;
import io.jburrow.server.support.TestContainersConfiguration;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HexFormat;
import java.util.List;
import java.util.SplittableRandom;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestClient;

/**
 * The end-to-end harness: the full server context on real Postgres, with the real client and real sockets on loopback.
 * Nothing on the byte path is mocked.
 *
 * <p>Each subclass names its own {@code jburrow.tcp.port-range} (a 100-port range above 40,000), so each gets a context
 * of its own and no two classes share tunnel ports. The port pool quarantines and retries a port another process holds,
 * so a collision with something else on the host is harmless.
 *
 * <p>Everything a test opens through this class is closed after it, in reverse order, so no tunnel holds a port into
 * the next test.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = "management.server.port=0")
@Import(TestContainersConfiguration.class)
@ActiveProfiles("test")
abstract class TunnelTestSupport {

    /** Test-only tokens for {@code application-test.yaml}'s {@code alice} and {@code bob}. */
    static final String ALICE_TOKEN = "jb_TEST-ONLY-alice-unlocks-nothing-real-000000";

    static final String BOB_TOKEN = "jb_TEST-ONLY-bob-unlocks-nothing-real-00000000";

    /** Well formed, and configured for no one. */
    static final String UNKNOWN_TOKEN = "jb_TEST-ONLY-nobody-unlocks-nothing-real-00000";

    static final Duration TIMEOUT = Duration.ofSeconds(10);

    private static final int CHUNK = 64 * 1024;

    @Autowired
    EdgeServer edge;

    @Autowired
    MeterRegistry meters;

    @LocalManagementPort
    int managementPort;

    private final Deque<AutoCloseable> resources = new ArrayDeque<>();

    /** A started client, its established session, and what it reported. */
    record Tunnel(JBurrowClient client, TunnelHandle handle, RecordingEvents events) {

        RegisteredTunnel registered() {
            return handle.tunnels().getFirst();
        }

        int port() {
            return registered().publicPort();
        }

        TunnelClosed awaitClosed(Duration timeout) throws InterruptedException {
            try {
                return handle.closed().toCompletableFuture().get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            } catch (ExecutionException | TimeoutException e) {
                throw new AssertionError("The session didn't end within " + timeout, e);
            }
        }
    }

    @AfterEach
    void closeTestResources() throws Exception {
        Exception failure = null;
        for (AutoCloseable resource = resources.pollLast(); resource != null; resource = resources.pollLast()) {
            try {
                resource.close();
            } catch (Exception e) {
                if (failure == null) {
                    failure = e;
                } else {
                    failure.addSuppressed(e);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    /** Closes {@code resource} after the test, before anything registered earlier. */
    <T extends AutoCloseable> T closeAfterTest(T resource) {
        resources.addLast(resource);
        return resource;
    }

    URI connectUri() {
        return URI.create("ws://localhost:" + edge.boundPort() + ScriptedPeer.CONNECT_PATH);
    }

    /** A tunnel for {@code alice} to {@code service}, on a port the server chooses. */
    Tunnel openTunnel(LocalService service) throws TunnelStartException {
        return openTunnel(ALICE_TOKEN, tcp(service.port()));
    }

    Tunnel openTunnel(String token, TunnelSpec spec) throws TunnelStartException {
        return openTunnel(client(token, spec));
    }

    /** Starts the client {@code builder} describes, with a {@link RecordingEvents} of its own. */
    Tunnel openTunnel(JBurrowClient.Builder builder) throws TunnelStartException {
        RecordingEvents events = new RecordingEvents();
        JBurrowClient client = closeAfterTest(builder.events(events).build());
        return new Tunnel(client, client.start(), events);
    }

    JBurrowClient.Builder client(String token, TunnelSpec spec) {
        return JBurrowClient.builder()
                .server(connectUri())
                .authtoken(token)
                .tunnel(spec)
                .clientInfo("jburrow-e2e", "1.0.0");
    }

    static TunnelSpec tcp(int localPort) {
        return TunnelSpec.tcp("default", LocalTarget.of("127.0.0.1", localPort));
    }

    /** A scripted client, connected and upgraded, that has sent nothing yet. */
    ScriptedPeer scriptedClient() {
        return closeAfterTest(ScriptedPeer.connect(connectUri(), ProtocolVersion.CURRENT));
    }

    /** {@code HELLO} and {@code REGISTER} for {@code alice}, answered; the session is established. */
    static RegisteredTunnel establish(ScriptedPeer client) {
        client.send(
                new Frame.Hello(new Hello(new ClientInfo("jburrow-scripted", "1.0.0", "linux", "amd64"), List.of())));
        client.send(new Frame.Register(
                new Register(ALICE_TOKEN, List.of(new TunnelRequest("default", TunnelKind.TCP, null)))));
        client.expect(Frame.ServerHello.class, TIMEOUT);
        return client.expect(Frame.Registered.class, TIMEOUT)
                .message()
                .tunnels()
                .getFirst();
    }

    /** A visitor connected to a tunnel's public port, with reads bounded by {@link #TIMEOUT}. */
    Socket visit(int port) throws IOException {
        Socket visitor = closeAfterTest(new Socket());
        visitor.setTcpNoDelay(true);
        visitor.setSoTimeout((int) TIMEOUT.toMillis());
        visitor.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), (int) TIMEOUT.toMillis());
        return visitor;
    }

    /** Writes {@code message} and reads back exactly as many bytes. */
    static byte[] roundTrip(Socket visitor, byte[] message) throws IOException {
        visitor.getOutputStream().write(message);
        visitor.getOutputStream().flush();
        return visitor.getInputStream().readNBytes(message.length);
    }

    /** Whether a listener could bind {@code port} on every interface now, as a tunnel listener does. */
    static boolean bindable(int port) {
        try (ServerSocket probe = new ServerSocket()) {
            probe.setReuseAddress(true);
            probe.bind(new InetSocketAddress(port));
            return true;
        } catch (IOException inUse) {
            return false;
        }
    }

    /** A loopback port nothing listens on, at the moment it is returned. */
    static int unusedPort() throws IOException {
        try (ServerSocket probe = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return probe.getLocalPort();
        }
    }

    /** Polls {@code condition} until it holds, failing after {@link #TIMEOUT}. */
    static void await(String what, BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TIMEOUT.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("Timed out after " + TIMEOUT + " waiting until " + what);
            }
            Thread.sleep(10);
        }
    }

    /** The count of a counter, which must exist. */
    double counter(String name, String... tags) {
        return meters.get(name).tags(tags).counter().count();
    }

    /** The value of a gauge, which must exist. */
    double gauge(String name, String... tags) {
        return meters.get(name).tags(tags).gauge().value();
    }

    /** Waits until the server holds no stream, so a new visitor gets one. */
    void awaitNoStreams() throws InterruptedException {
        await("the server holds no stream", () -> gauge("jburrow.streams.active", "kind", "tcp") == 0);
    }

    /** Waits until every tunnel port is back in the pool. */
    void awaitNoReservedPorts() throws InterruptedException {
        await("every tunnel port is released", () -> gauge("jburrow.tcp.ports", "state", "reserved") == 0);
    }

    /** The whole Prometheus scrape from the management port. */
    String scrape() {
        return RestClient.create("http://localhost:" + managementPort)
                .get()
                .uri("/actuator/prometheus")
                .retrieve()
                .body(String.class);
    }

    // Payloads: deterministic bytes, so a transfer is checked against a digest instead of a copy.

    /** Writes {@code length} bytes generated from {@code seed}, and returns their SHA-256. */
    static String send(OutputStream out, long length, long seed) throws IOException {
        MessageDigest sha256 = sha256();
        SplittableRandom random = new SplittableRandom(seed);
        byte[] chunk = new byte[CHUNK];
        for (long left = length; left > 0; left -= chunk.length) {
            int size = (int) Math.min(chunk.length, left);
            random.nextBytes(chunk);
            out.write(chunk, 0, size);
            sha256.update(chunk, 0, size);
        }
        out.flush();
        return HexFormat.of().formatHex(sha256.digest());
    }

    /** The SHA-256 of what {@link #send} writes for {@code length} and {@code seed}. */
    static String expectedDigest(long length, long seed) throws IOException {
        return send(OutputStream.nullOutputStream(), length, seed);
    }

    /** Bytes read until the end of a stream, and their SHA-256. */
    record Received(long length, String sha256) {}

    /** Reads {@code in} to its end. */
    static Received drain(InputStream in) throws IOException {
        MessageDigest sha256 = sha256();
        byte[] buffer = new byte[CHUNK];
        long length = 0;
        for (int read = in.read(buffer); read >= 0; read = in.read(buffer)) {
            sha256.update(buffer, 0, read);
            length += read;
        }
        return new Received(length, HexFormat.of().formatHex(sha256.digest()));
    }

    static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static void assertOpen(TunnelHandle handle) {
        assertThat(handle.closed().toCompletableFuture())
                .as("session still open")
                .isNotDone();
    }
}
