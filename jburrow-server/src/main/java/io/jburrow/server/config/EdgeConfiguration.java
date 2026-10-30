package io.jburrow.server.config;

import io.jburrow.protocol.wire.Capability;
import io.jburrow.server.api.instance.application.port.BuildIdentitySource;
import io.jburrow.server.core.portpool.application.PortReservationService;
import io.jburrow.server.core.portpool.domain.PortPool;
import io.jburrow.server.core.portpool.domain.PortRange;
import io.jburrow.server.core.routing.application.SubdomainRoutes;
import io.jburrow.server.core.routing.domain.Blocklist;
import io.jburrow.server.core.routing.domain.NameGenerator;
import io.jburrow.server.core.tunnel.adapter.netty.control.ControlSessionInitializer;
import io.jburrow.server.core.tunnel.adapter.netty.control.SessionRegistry;
import io.jburrow.server.core.tunnel.adapter.netty.edge.Cidr;
import io.jburrow.server.core.tunnel.adapter.netty.edge.EdgeServer;
import io.jburrow.server.core.tunnel.adapter.netty.edge.EdgeSettings;
import io.jburrow.server.core.tunnel.adapter.netty.edge.EngineDependencies;
import io.jburrow.server.core.tunnel.adapter.netty.edge.HttpSettings;
import io.jburrow.server.core.tunnel.adapter.netty.edge.TcpSettings;
import io.jburrow.server.core.tunnel.adapter.netty.http.SessionExchanges;
import io.jburrow.server.core.tunnel.adapter.netty.tcp.NettyTunnelListeners;
import io.jburrow.server.core.tunnel.application.RegisterTunnels;
import io.jburrow.server.core.tunnel.application.RegistrationPolicy;
import io.jburrow.server.core.tunnel.application.SessionAdmission;
import io.jburrow.server.core.tunnel.application.StreamQuota;
import io.jburrow.server.core.tunnel.application.port.AuthtokenVerifier;
import io.jburrow.server.core.tunnel.domain.Principal;
import io.jburrow.server.core.tunnel.domain.TunnelId;
import io.jburrow.server.observability.MicrometerTunnelMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Constructs the tunnel engine from {@code jburrow.edge.*}, {@code jburrow.tcp.*} and {@code jburrow.http.*}: the port
 * pool, the route table, the session and stream caps, registration, the blocking executor, the metrics adapter, the
 * TCP tunnel listeners and the {@link EdgeServer}, which routes each HTTP request to the session serving its tunnel.
 * Hands the engine plain values and an executor, so nothing here touches Netty. Logs the direct memory budget the
 * settings add up to, parked responses included, and the kernel memory streams' socket buffers can take.
 */
@Configuration(proxyBeanMethods = false)
class EdgeConfiguration {

    private static final Logger log = LoggerFactory.getLogger(EdgeConfiguration.class);

    /** Phase 1 serves one tunnel per session; phase 2 raises it with multiplexing. */
    static final int TUNNELS_PER_SESSION = 1;

    /** How long a port whose bind found it in use is held out of the pool. */
    static final Duration PORT_QUARANTINE = Duration.ofMinutes(10);

    /** The capabilities the server enables: a client that offers {@code http} gets HTTP tunnels. */
    static final Set<Capability> CAPABILITIES = Set.of(Capability.HTTP);

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

    /** The route table: claimed by registration, read by the edge. */
    @Bean
    SubdomainRoutes<Principal, TunnelId> subdomainRoutes(JBurrowProperties properties) {
        Set<String> reserved = reservedNames(
                properties.http(),
                properties.edge().connectHost(),
                properties.tcp().publicHost());
        return new SubdomainRoutes<>(
                Blocklist.defaults(reserved),
                new NameGenerator(new SecureRandom()),
                properties.http().nameHold(),
                InstantSource.system());
    }

    @Bean
    RegisterTunnels registerTunnels(
            AuthtokenVerifier verifier,
            SessionAdmission admission,
            PortReservationService ports,
            SubdomainRoutes<Principal, TunnelId> routes,
            MicrometerTunnelMetrics metrics,
            JBurrowProperties properties) {
        JBurrowProperties.Http http = properties.http();
        return new RegisterTunnels(
                verifier,
                admission,
                ports,
                routes,
                new RegistrationPolicy(
                        TUNNELS_PER_SESSION,
                        properties.tcp().publicHost(),
                        new RegistrationPolicy.HttpPublic(http.domain(), http.publicScheme(), http.publicPort())),
                metrics);
    }

    /**
     * {@code jburrow.http.reserved-names}, plus the label of {@code jburrow.edge.connect-host} and of
     * {@code jburrow.tcp.public-host} when either is one label under the HTTP domain, so no tunnel can take the
     * service's own hosts.
     *
     * @throws IllegalStateException if either host is two or more labels under the domain, where the wildcard tunnel
     *     ingress can't reach it
     */
    static Set<String> reservedNames(JBurrowProperties.Http http, String connectHost, String tcpPublicHost) {
        Set<String> reserved = new TreeSet<>(http.reservedNames());
        Set<String> serviceLabels = new TreeSet<>();
        reserveServiceLabel(http, connectHost, "jburrow.edge.connect-host", serviceLabels);
        reserveServiceLabel(http, tcpPublicHost, "jburrow.tcp.public-host", serviceLabels);
        if (!serviceLabels.isEmpty()) {
            log.info("Reserved subdomains for the service's own hosts: {}", serviceLabels);
        }
        reserved.addAll(serviceLabels);
        return reserved;
    }

    private static void reserveServiceLabel(JBurrowProperties.Http http, String host, String key, Set<String> labels) {
        http.labelsUnderDomain(host).ifPresent(under -> {
            if (under.contains(".")) {
                throw new IllegalStateException("Invalid " + key + ": " + host + " is more than one label under"
                        + " jburrow.http.domain " + http.domain() + ", so the tunnel ingress can't reach it");
            }
            labels.add(under);
        });
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
            SubdomainRoutes<Principal, TunnelId> routes,
            MicrometerTunnelMetrics metrics,
            SessionRegistry sessionRegistry,
            BlockingExecutor blocking) {
        JBurrowProperties.Tcp tcp = properties.tcp();
        TcpSettings tcpSettings = new TcpSettings(
                ipLiteral(tcp.bindAddress(), "jburrow.tcp.bind-address").getHostAddress(), tcp.publicHost(), (int)
                        tcp.visitorReceiveBuffer().toBytes());
        EngineDependencies deps = new EngineDependencies(
                registerTunnels, admission, streamQuota, ports, routes, metrics, blocking.executor(), tcpSettings);
        EdgeSettings settings = edgeSettings(
                properties.edge(), properties.http(), build.current().version());
        EdgeServer edge = new EdgeServer(
                settings,
                deps,
                new ControlSessionInitializer(
                        settings, deps, sessionRegistry, new NettyTunnelListeners(settings, deps)),
                SessionExchanges.in(sessionRegistry));
        metrics.bindDirectMemory(edge::usedDirectMemory);
        logBudget(DirectMemoryBudget.of(properties.edge(), properties.http(), edge.maxDirectMemory()));
        logBudget(KernelBufferBudget.of(properties.edge(), tcp, properties.http()));
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
    static void logBudget(KernelBufferBudget budget) {
        if (budget.kernelSized()) {
            log.warn("Stream socket buffers: {}", budget.describe());
        } else {
            log.info("Stream socket buffers: {}", budget.describe());
        }
    }

    @Bean
    EdgeServerLifecycle edgeServerLifecycle(EdgeServer edge) {
        return new EdgeServerLifecycle(edge);
    }

    static EdgeSettings edgeSettings(JBurrowProperties.Edge edge, JBurrowProperties.Http http, String serverVersion) {
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
                serverVersion,
                httpSettings(http),
                CAPABILITIES);
    }

    static HttpSettings httpSettings(JBurrowProperties.Http http) {
        return new HttpSettings(
                http.domain(),
                http.maxRequestBody().toBytes(),
                http.responseTimeout(),
                http.keepAliveTimeout(),
                (int) http.socketBuffer().toBytes(),
                http.parkAfter(),
                http.maxParked().toBytes(),
                http.maxParkedPerSession().toBytes());
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
