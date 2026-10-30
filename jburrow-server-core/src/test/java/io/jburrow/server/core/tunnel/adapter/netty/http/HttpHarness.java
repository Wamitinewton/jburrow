package io.jburrow.server.core.tunnel.adapter.netty.http;

import static org.assertj.core.api.Assertions.assertThat;

import io.jburrow.protocol.adapter.netty.Frame;
import io.jburrow.protocol.control.ClientInfo;
import io.jburrow.protocol.control.Hello;
import io.jburrow.protocol.control.Register;
import io.jburrow.protocol.control.RegisteredTunnel;
import io.jburrow.protocol.control.TunnelKind;
import io.jburrow.protocol.control.TunnelRequest;
import io.jburrow.protocol.control.Windows;
import io.jburrow.protocol.testsupport.ScriptedPeer;
import io.jburrow.protocol.wire.Capability;
import io.jburrow.protocol.wire.ProtocolVersion;
import io.jburrow.server.core.portpool.application.PortReservationService;
import io.jburrow.server.core.portpool.domain.PortPool;
import io.jburrow.server.core.portpool.domain.PortRange;
import io.jburrow.server.core.routing.application.SubdomainRoutes;
import io.jburrow.server.core.tunnel.adapter.netty.control.ControlSessionInitializer;
import io.jburrow.server.core.tunnel.adapter.netty.control.SessionContext;
import io.jburrow.server.core.tunnel.adapter.netty.control.SessionRegistry;
import io.jburrow.server.core.tunnel.adapter.netty.edge.Cidr;
import io.jburrow.server.core.tunnel.adapter.netty.edge.EdgeConnections;
import io.jburrow.server.core.tunnel.adapter.netty.edge.EdgeServer;
import io.jburrow.server.core.tunnel.adapter.netty.edge.EdgeSettings;
import io.jburrow.server.core.tunnel.adapter.netty.edge.EdgeTransport;
import io.jburrow.server.core.tunnel.adapter.netty.edge.EngineDependencies;
import io.jburrow.server.core.tunnel.adapter.netty.edge.HttpSettings;
import io.jburrow.server.core.tunnel.adapter.netty.edge.TcpSettings;
import io.jburrow.server.core.tunnel.adapter.netty.tcp.NettyTunnelListeners;
import io.jburrow.server.core.tunnel.application.InMemoryAuthtokenVerifier;
import io.jburrow.server.core.tunnel.application.RecordingTunnelMetrics;
import io.jburrow.server.core.tunnel.application.RegisterTunnels;
import io.jburrow.server.core.tunnel.application.RegistrationFixtures;
import io.jburrow.server.core.tunnel.application.SessionAdmission;
import io.jburrow.server.core.tunnel.application.StreamQuota;
import io.jburrow.server.core.tunnel.domain.Principal;
import io.jburrow.server.core.tunnel.domain.TunnelId;
import io.netty.channel.Channel;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Duration;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/**
 * A real edge serving HTTP exchanges on loopback, with {@link ScriptedPeer}s playing the client and plain sockets
 * playing visitors, so the server's half of an exchange is tested without the real client. The edge has two event
 * loops, so a session and an edge connection can be put on different ones; {@link #visitorOnAnotherLoop} does.
 */
final class HttpHarness implements AutoCloseable {

    static final String TOKEN = "jb_" + "h".repeat(43);
    static final Duration TIMEOUT = Duration.ofSeconds(10);
    static final Windows SERVER_WINDOWS = new Windows(256 * 1024, 512 * 1024);

    /**
     * What a test may change about the server it runs against. Edge connections' socket buffers are the kernel's by
     * default, as in the {@code local} profile: on loopback a 16 KiB buffer is smaller than one segment.
     */
    record Settings(EdgeTransport transport, HttpSettings http, int maxConcurrentStreams, int maxStreams) {

        static Settings defaults(EdgeTransport transport) {
            return new Settings(
                    transport,
                    new HttpSettings(
                            "localhost",
                            1024L * 1024 * 1024,
                            Duration.ofSeconds(60),
                            Duration.ofSeconds(75),
                            HttpSettings.KERNEL_SIZED,
                            Duration.ofSeconds(1),
                            16L * 1024 * 1024,
                            2L * 1024 * 1024),
                    100,
                    200);
        }

        Settings http(HttpSettings http) {
            return new Settings(transport, http, maxConcurrentStreams, maxStreams);
        }

        Settings streams(int maxConcurrentStreams, int maxStreams) {
            return new Settings(transport, http, maxConcurrentStreams, maxStreams);
        }

        Settings maxRequestBody(long bytes) {
            return http(new HttpSettings(
                    http.domain(),
                    bytes,
                    http.responseTimeout(),
                    http.keepAliveTimeout(),
                    http.socketBuffer(),
                    http.parkAfter(),
                    http.maxParked(),
                    http.maxParkedPerSession()));
        }

        Settings responseTimeout(Duration timeout) {
            return http(new HttpSettings(
                    http.domain(),
                    http.maxRequestBody(),
                    timeout,
                    http.keepAliveTimeout(),
                    http.socketBuffer(),
                    http.parkAfter(),
                    http.maxParked(),
                    http.maxParkedPerSession()));
        }

        /** Parking, with {@code socketBuffer} bounding what a response can leave in the kernel before it stalls. */
        Settings parking(Duration parkAfter, long maxParked, long maxParkedPerSession, int socketBuffer) {
            return http(new HttpSettings(
                    http.domain(),
                    http.maxRequestBody(),
                    http.responseTimeout(),
                    http.keepAliveTimeout(),
                    socketBuffer,
                    parkAfter,
                    maxParked,
                    maxParkedPerSession));
        }
    }

    final RecordingTunnelMetrics metrics = new RecordingTunnelMetrics();
    final StreamQuota streamQuota;
    final SessionRegistry registry = new SessionRegistry();
    final EdgeServer edge;

    private final ExecutorService blocking = Executors.newVirtualThreadPerTaskExecutor();
    private final List<ScriptedPeer> peers = new ArrayList<>();
    private final List<Visitor> visitors = new ArrayList<>();

    HttpHarness(Settings settings) {
        streamQuota = new StreamQuota(settings.maxStreams());
        EdgeSettings edgeSettings = new EdgeSettings(
                new InetSocketAddress(InetAddress.getLoopbackAddress(), 0),
                "localhost",
                settings.transport(),
                2,
                List.of(Cidr.parse("127.0.0.1/32"), Cidr.parse("::1/128")),
                16,
                8,
                Duration.ofSeconds(5),
                Duration.ofSeconds(5),
                Duration.ofSeconds(15),
                Duration.ofSeconds(45),
                64 * 1024,
                256 * 1024,
                SERVER_WINDOWS,
                settings.maxConcurrentStreams(),
                settings.maxStreams(),
                "test",
                settings.http(),
                Set.of(Capability.HTTP));
        SubdomainRoutes<Principal, TunnelId> routes = RegistrationFixtures.routes();
        PortReservationService ports = new PortReservationService(new PortPool(
                PortRange.parse("42100-42109"),
                Duration.ofSeconds(60),
                Duration.ofMinutes(10),
                new Random(3),
                InstantSource.system()));
        SessionAdmission admission = new SessionAdmission(16, 8, 8);
        RegisterTunnels register = new RegisterTunnels(
                new InMemoryAuthtokenVerifier().with(TOKEN, "tester"),
                admission,
                ports,
                routes,
                RegistrationFixtures.policy(1, "localhost"),
                metrics);
        EngineDependencies deps = new EngineDependencies(
                register,
                admission,
                streamQuota,
                ports,
                routes,
                metrics,
                blocking,
                new TcpSettings("127.0.0.1", "localhost", 32 * 1024));
        ControlSessionInitializer sessions = new ControlSessionInitializer(
                edgeSettings, deps, registry, new NettyTunnelListeners(edgeSettings, deps));
        edge = new EdgeServer(edgeSettings, deps, sessions, SessionExchanges.in(registry));
        edge.start();
    }

    /** A client connected to the connect host, {@code localhost}; tunnels are {@code <name>.localhost}. */
    ScriptedPeer connect() {
        ScriptedPeer peer = ScriptedPeer.connect(
                URI.create("ws://localhost:" + edge.boundPort() + ScriptedPeer.CONNECT_PATH), ProtocolVersion.CURRENT);
        peers.add(peer);
        return peer;
    }

    /** Says {@code HELLO} offering {@code http} with {@code windows}, then registers one HTTP tunnel. */
    RegisteredTunnel establish(ScriptedPeer peer, String subdomain, Windows windows) {
        peer.send(new Frame.Hello(new Hello(
                new ClientInfo("jburrow-test", "1.0", "linux", "amd64"),
                List.of(Capability.HTTP.wireName()),
                windows)));
        assertThat(peer.expect(Frame.ServerHello.class, TIMEOUT).message().capabilities())
                .containsExactly(Capability.HTTP.wireName());
        peer.send(new Frame.Register(
                new Register(TOKEN, List.of(new TunnelRequest("default", TunnelKind.HTTP, subdomain, null)))));
        return peer.expect(Frame.Registered.class, TIMEOUT).message().tunnels().getFirst();
    }

    RegisteredTunnel establish(ScriptedPeer peer, String subdomain) {
        return establish(peer, subdomain, ScriptedPeer.CLIENT_WINDOWS);
    }

    /** A visitor connected to the edge; its requests name {@code host}, at the edge's port. */
    Visitor visitor(String host) throws IOException {
        Visitor visitor = new Visitor(edge.boundPort(), host + ":" + edge.boundPort(), 0);
        visitors.add(visitor);
        return visitor;
    }

    /** A visitor whose socket takes at most {@code receiveBuffer} bytes before it reads, so it can stall a response. */
    Visitor slowVisitor(String host, int receiveBuffer) throws IOException {
        Visitor visitor = new Visitor(edge.boundPort(), host + ":" + edge.boundPort(), receiveBuffer);
        visitors.add(visitor);
        return visitor;
    }

    /**
     * A visitor whose edge connection is on another event loop than {@code session}'s, so every crossing between the
     * two halves of its exchanges is a real hand-over between threads.
     */
    Visitor visitorOnAnotherLoop(String host, SessionContext session) throws IOException {
        for (int attempt = 0; attempt < 4; attempt++) {
            Visitor visitor = visitor(host);
            Channel connection = edgeConnection(visitor);
            if (connection.eventLoop() != session.channel().eventLoop()) {
                return visitor;
            }
        }
        throw new AssertionError("No edge connection landed on another loop than the session's");
    }

    /** The edge's side of {@code visitor}'s connection. */
    Channel edgeConnection(Visitor visitor) {
        int port = visitor.localPort();
        awaitTrue(() -> EdgeConnections.of(edge).stream().anyMatch(channel -> remotePort(channel) == port));
        return EdgeConnections.of(edge).stream()
                .filter(channel -> remotePort(channel) == port)
                .findFirst()
                .orElseThrow();
    }

    private static int remotePort(Channel channel) {
        return channel.remoteAddress() instanceof InetSocketAddress address ? address.getPort() : -1;
    }

    /** The one live session. */
    SessionContext onlySession() {
        awaitTrue(() -> registry.active() == 1);
        return registry.all().getFirst();
    }

    /** Runs {@code task} on the session's event loop, where its state may be read. */
    static <T> T onLoop(SessionContext session, Callable<T> task) throws Exception {
        return session.channel().eventLoop().submit(task).get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
    }

    static void awaitTrue(BooleanSupplier condition) {
        long deadline = System.nanoTime() + TIMEOUT.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("Condition not met within " + TIMEOUT);
            }
            try {
                TimeUnit.MILLISECONDS.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted", e);
            }
        }
    }

    @Override
    public void close() throws Exception {
        for (Visitor visitor : visitors) {
            visitor.close();
        }
        peers.forEach(ScriptedPeer::close);
        edge.stop(Duration.ofSeconds(15)).get(20, TimeUnit.SECONDS);
        blocking.shutdownNow();
    }
}
