package io.jburrow.server.core.tunnel.adapter.netty.tcp;

import io.jburrow.protocol.adapter.netty.Frame;
import io.jburrow.protocol.control.ClientInfo;
import io.jburrow.protocol.control.Hello;
import io.jburrow.protocol.control.Register;
import io.jburrow.protocol.control.RegisteredTunnel;
import io.jburrow.protocol.control.TunnelKind;
import io.jburrow.protocol.control.TunnelRequest;
import io.jburrow.protocol.testsupport.ScriptedPeer;
import io.jburrow.protocol.wire.ProtocolVersion;
import io.jburrow.server.core.portpool.application.PortReservationService;
import io.jburrow.server.core.portpool.domain.PortPool;
import io.jburrow.server.core.portpool.domain.PortRange;
import io.jburrow.server.core.tunnel.adapter.netty.control.ControlSessionInitializer;
import io.jburrow.server.core.tunnel.adapter.netty.control.SessionContext;
import io.jburrow.server.core.tunnel.adapter.netty.edge.Cidr;
import io.jburrow.server.core.tunnel.adapter.netty.edge.EdgeServer;
import io.jburrow.server.core.tunnel.adapter.netty.edge.EdgeSettings;
import io.jburrow.server.core.tunnel.adapter.netty.edge.EdgeTransport;
import io.jburrow.server.core.tunnel.adapter.netty.edge.EngineDependencies;
import io.jburrow.server.core.tunnel.adapter.netty.edge.TcpSettings;
import io.jburrow.server.core.tunnel.application.InMemoryAuthtokenVerifier;
import io.jburrow.server.core.tunnel.application.RecordingTunnelMetrics;
import io.jburrow.server.core.tunnel.application.RegisterTunnels;
import io.jburrow.server.core.tunnel.application.RegistrationPolicy;
import io.jburrow.server.core.tunnel.application.SessionAdmission;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.time.Duration;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.random.RandomGenerator;

/**
 * A real edge with real TCP tunnel listeners on loopback, and {@link ScriptedPeer}s playing the client, so the
 * server's byte path is tested without the real client.
 */
final class TunnelHarness implements AutoCloseable {

    static final String TOKEN = "jb_" + "a".repeat(43);
    static final String PUBLIC_HOST = "localhost";
    static final Duration TIMEOUT = Duration.ofSeconds(10);

    static final int SESSION_WRITE_LOW = 128 * 1024;
    static final int SESSION_WRITE_HIGH = 512 * 1024;
    static final int VISITOR_WRITE_LOW = 32 * 1024;
    static final int VISITOR_WRITE_HIGH = 128 * 1024;

    private static final Duration STOP_TIMEOUT = Duration.ofSeconds(15);

    final RecordingTunnelMetrics metrics = new RecordingTunnelMetrics();
    final SessionAdmission admission = new SessionAdmission(16, 8, 3);
    final PortReservationService ports;
    final EdgeServer edge;
    final ControlSessionInitializer sessions;

    private final ExecutorService blocking = Executors.newVirtualThreadPerTaskExecutor();
    private final List<ScriptedPeer> peers = new ArrayList<>();

    TunnelHarness(PortRange range, RandomGenerator random, Duration pingInterval, Duration idleTimeout) {
        ports = new PortReservationService(
                new PortPool(range, Duration.ofSeconds(60), Duration.ofMinutes(10), random, InstantSource.system()));
        EdgeSettings settings = new EdgeSettings(
                new InetSocketAddress(InetAddress.getLoopbackAddress(), 0),
                "localhost",
                EdgeTransport.AUTO,
                2,
                List.of(Cidr.parse("127.0.0.1/32"), Cidr.parse("::1/128")),
                16,
                8,
                Duration.ofSeconds(5),
                Duration.ofSeconds(5),
                pingInterval,
                idleTimeout,
                SESSION_WRITE_LOW,
                SESSION_WRITE_HIGH,
                VISITOR_WRITE_LOW,
                VISITOR_WRITE_HIGH,
                "test");
        RegisterTunnels register = new RegisterTunnels(
                new InMemoryAuthtokenVerifier().with(TOKEN, "tester"),
                admission,
                ports,
                new RegistrationPolicy(1, PUBLIC_HOST));
        EngineDependencies deps = new EngineDependencies(
                register, admission, ports, metrics, blocking, new TcpSettings("127.0.0.1", PUBLIC_HOST));
        sessions = new ControlSessionInitializer(settings, deps, new NettyTunnelListeners(settings, deps));
        edge = new EdgeServer(settings, deps, sessions);
        edge.start();
    }

    ScriptedPeer connect() {
        ScriptedPeer peer = ScriptedPeer.connect(
                URI.create("ws://localhost:" + edge.boundPort() + ScriptedPeer.CONNECT_PATH), ProtocolVersion.CURRENT);
        peers.add(peer);
        return peer;
    }

    /** Says {@code HELLO} and registers one TCP tunnel, on {@code remotePort} if not null. */
    static void register(ScriptedPeer peer, Integer remotePort) {
        peer.send(new Frame.Hello(new Hello(new ClientInfo("jburrow-test", "1.0", "linux", "amd64"), List.of())));
        peer.send(new Frame.Register(
                new Register(TOKEN, List.of(new TunnelRequest("default", TunnelKind.TCP, remotePort)))));
        peer.expect(Frame.ServerHello.class, TIMEOUT);
    }

    /** A connected, established peer and its tunnel. */
    RegisteredTunnel establish(ScriptedPeer peer, Integer remotePort) {
        register(peer, remotePort);
        return peer.expect(Frame.Registered.class, TIMEOUT).message().tunnels().getFirst();
    }

    /** The one live session. */
    SessionContext onlySession() {
        awaitTrue(() -> sessions.registry().active() == 1);
        return sessions.registry().all().getFirst();
    }

    /** Runs {@code task} on the session's event loop, where its state may be read. */
    static <T> T onLoop(SessionContext session, Callable<T> task) throws Exception {
        return session.channel().eventLoop().submit(task).get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
    }

    static boolean canBind(int port) {
        try (ServerSocket socket = new ServerSocket()) {
            socket.setReuseAddress(true);
            socket.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), port));
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    static void awaitTrue(BooleanSupplier condition) {
        long deadline = System.nanoTime() + TIMEOUT.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("Condition not met within " + TIMEOUT);
            }
            try {
                TimeUnit.MILLISECONDS.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted", e);
            }
        }
    }

    @Override
    public void close() throws Exception {
        peers.forEach(ScriptedPeer::close);
        edge.stop(STOP_TIMEOUT).get(20, TimeUnit.SECONDS);
        blocking.shutdownNow();
    }
}
