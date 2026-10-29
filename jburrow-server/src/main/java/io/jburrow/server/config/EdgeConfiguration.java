package io.jburrow.server.config;

import io.jburrow.server.api.instance.application.port.BuildIdentitySource;
import io.jburrow.server.core.portpool.application.PortReservationService;
import io.jburrow.server.core.portpool.domain.PortPool;
import io.jburrow.server.core.portpool.domain.PortRange;
import io.jburrow.server.core.tunnel.adapter.netty.control.ControlSessionInitializer;
import io.jburrow.server.core.tunnel.adapter.netty.control.SessionRegistry;
import io.jburrow.server.core.tunnel.adapter.netty.edge.Cidr;
import io.jburrow.server.core.tunnel.adapter.netty.edge.EdgeServer;
import io.jburrow.server.core.tunnel.adapter.netty.edge.EdgeSettings;
import io.jburrow.server.core.tunnel.adapter.netty.edge.EngineDependencies;
import io.jburrow.server.core.tunnel.adapter.netty.edge.TcpSettings;
import io.jburrow.server.core.tunnel.adapter.netty.tcp.NettyTunnelListeners;
import io.jburrow.server.core.tunnel.application.RegisterTunnels;
import io.jburrow.server.core.tunnel.application.RegistrationPolicy;
import io.jburrow.server.core.tunnel.application.SessionAdmission;
import io.jburrow.server.core.tunnel.application.StreamQuota;
import io.jburrow.server.core.tunnel.application.port.AuthtokenVerifier;
import io.jburrow.server.observability.MicrometerTunnelMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Constructs the tunnel engine from {@code jburrow.edge.*} and {@code jburrow.tcp.*}: the port pool, the session and
 * stream caps, registration, the blocking executor, the metrics adapter, the TCP tunnel listeners and the
 * {@link EdgeServer}. Hands the engine plain values and an executor, so nothing here touches Netty. Logs the direct
 * memory budget the settings add up to, and the kernel memory visitors' receive buffers can take.
 */
@Configuration(proxyBeanMethods = false)
class EdgeConfiguration {

    private static final Logger log = LoggerFactory.getLogger(EdgeConfiguration.class);

    /** Phase 1 serves one tunnel per session; phase 2 raises it with multiplexing. */
    static final int TUNNELS_PER_SESSION = 1;

    /** How long a port whose bind found it in use is held out of the pool. */
    static final Duration PORT_QUARANTINE = Duration.ofMinutes(10);

    @Bean
    PortReservationService portReservations(JBurrowProperties properties, Environment environment) {
        PortRange range = properties.tcp().parsedPortRange().orElseThrow();
        requireClear(range, environment, "server.port");
        requireClear(range, environment, "management.server.port");
        return new PortReservationService(new PortPool(
                range, properties.tcp().reuseDelay(), PORT_QUARANTINE, new SecureRandom(), InstantSource.system()));
    }

    @Bean
    SessionAdmission sessionAdmission(JBurrowProperties properties) {
        JBurrowProperties.Edge edge = properties.edge();
        return new SessionAdmission(edge.maxSessions(), edge.maxHandshaking(), edge.maxSessionsPerPrincipal());
    }

    @Bean
    StreamQuota streamQuota(JBurrowProperties properties) {
        return new StreamQuota(properties.edge().maxStreams());
    }

    @Bean
    RegisterTunnels registerTunnels(
            AuthtokenVerifier verifier,
            SessionAdmission admission,
            PortReservationService ports,
            JBurrowProperties properties) {
        return new RegisterTunnels(
                verifier,
                admission,
                ports,
                new RegistrationPolicy(TUNNELS_PER_SESSION, properties.tcp().publicHost()));
    }

    @Bean
    MicrometerTunnelMetrics tunnelMetrics(MeterRegistry registry, PortReservationService ports) {
        MicrometerTunnelMetrics metrics = new MicrometerTunnelMetrics(registry);
        metrics.bindPortPool(ports::snapshot);
        return metrics;
    }

    /** Every live session, which the edge keeps and anything outside it finds sessions through. */
    @Bean
    SessionRegistry sessionRegistry() {
        return new SessionRegistry();
    }

    @Bean
    BlockingExecutor blockingExecutor() {
        return new BlockingExecutor(Executors.newVirtualThreadPerTaskExecutor());
    }

    @Bean
    EdgeServer edgeServer(
            JBurrowProperties properties,
            BuildIdentitySource build,
            RegisterTunnels registerTunnels,
            SessionAdmission admission,
            StreamQuota streamQuota,
            PortReservationService ports,
            MicrometerTunnelMetrics metrics,
            SessionRegistry sessionRegistry,
            BlockingExecutor blocking) {
        JBurrowProperties.Tcp tcp = properties.tcp();
        TcpSettings tcpSettings = new TcpSettings(
                ipLiteral(tcp.bindAddress(), "jburrow.tcp.bind-address").getHostAddress(), tcp.publicHost(), (int)
                        tcp.visitorReceiveBuffer().toBytes());
        EngineDependencies deps = new EngineDependencies(
                registerTunnels, admission, streamQuota, ports, metrics, blocking.executor(), tcpSettings);
        EdgeSettings settings = edgeSettings(properties.edge(), build.current().version());
        EdgeServer edge = new EdgeServer(
                settings,
                deps,
                new ControlSessionInitializer(
                        settings, deps, sessionRegistry, new NettyTunnelListeners(settings, deps)));
        metrics.bindDirectMemory(edge::usedDirectMemory);
        logBudget(DirectMemoryBudget.of(properties.edge(), edge.maxDirectMemory()));
        logBudget(VisitorBufferBudget.of(properties.edge(), tcp));
        return edge;
    }

    /** Warns rather than failing: the worst case needs every session saturated at once, and may be overcommitted. */
    static void logBudget(DirectMemoryBudget budget) {
        if (budget.exceedsWarnShare()) {
            log.warn("Direct memory budget: {}, which exceeds 90% of direct memory", budget.describe());
        } else {
            log.info("Direct memory budget: {}", budget.describe());
        }
    }

    /** Kernel memory is outside the JVM, so this only states the figure; it is the pod limit's to absorb. */
    static void logBudget(VisitorBufferBudget budget) {
        if (budget.kernelSized()) {
            log.warn("Visitor receive buffers: {}", budget.describe());
        } else {
            log.info("Visitor receive buffers: {}", budget.describe());
        }
    }

    @Bean
    EdgeServerLifecycle edgeServerLifecycle(EdgeServer edge) {
        return new EdgeServerLifecycle(edge);
    }

    static EdgeSettings edgeSettings(JBurrowProperties.Edge edge, String serverVersion) {
        JBurrowProperties.Watermarks session = edge.sessionWatermarks();
        return new EdgeSettings(
                new InetSocketAddress(ipLiteral(edge.bindAddress(), "jburrow.edge.bind-address"), edge.port()),
                edge.connectHost(),
                edge.transport(),
                edge.effectiveIoThreads(),
                trustedProxies(edge.trustedProxies()),
                edge.maxSessions(),
                edge.maxHandshaking(),
                edge.requestTimeout(),
                edge.handshakeTimeout(),
                edge.pingInterval(),
                edge.idleTimeout(),
                session.low(),
                session.high(),
                edge.windows(),
                edge.maxConcurrentStreams(),
                edge.maxStreams(),
                serverVersion);
    }

    /** Stops startup on the first entry that isn't a CIDR, naming the key and the entry. */
    static List<Cidr> trustedProxies(List<String> entries) {
        List<Cidr> proxies = new ArrayList<>(entries.size());
        for (int i = 0; i < entries.size(); i++) {
            try {
                proxies.add(Cidr.parse(entries.get(i).strip()));
            } catch (IllegalArgumentException e) {
                throw new IllegalStateException(
                        "Invalid jburrow.edge.trusted-proxies (JBURROW_EDGE_TRUSTED_PROXIES), entry " + (i + 1) + ": "
                                + e.getMessage(),
                        e);
            }
        }
        return proxies;
    }

    private static InetAddress ipLiteral(String text, String key) {
        return Cidr.parseAddress(text.strip())
                .orElseThrow(() -> new IllegalStateException("Invalid " + key + ": must be an IP address literal"));
    }

    /**
     * The API and management ports are Spring's own keys, outside {@code JBurrowProperties}, so this rule is checked
     * here rather than by the binder.
     */
    static void requireClear(PortRange range, Environment environment, String portKey) {
        Integer port = environment.getProperty(portKey, Integer.class);
        if (port != null && range.contains(port)) {
            throw new IllegalStateException(
                    "Invalid jburrow.tcp.port-range: " + range + " contains " + portKey + " " + port);
        }
    }

    /**
     * The virtual-thread executor for blocking engine work, closed when the context closes. Not itself an
     * {@code Executor} bean: one would make Spring Boot back off its own application task executor.
     */
    record BlockingExecutor(ExecutorService executor) implements AutoCloseable {

        /** Interrupts anything still running: the edge has stopped, so no result would be used. */
        @Override
        public void close() {
            executor.shutdownNow();
        }
    }
}
