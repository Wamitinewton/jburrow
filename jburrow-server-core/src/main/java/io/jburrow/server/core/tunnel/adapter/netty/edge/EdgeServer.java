package io.jburrow.server.core.tunnel.adapter.netty.edge;

import io.jburrow.protocol.adapter.jackson.ControlJson;
import io.jburrow.server.core.tunnel.adapter.netty.http.EdgeErrorPage;
import io.jburrow.server.core.tunnel.adapter.netty.http.ExchangeHandler;
import io.jburrow.server.core.tunnel.adapter.netty.http.ExchangeServices;
import io.jburrow.server.core.tunnel.adapter.netty.http.ExchangeSessions;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.ByteBufAllocatorMetricProvider;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.IoHandlerFactory;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.ServerChannel;
import io.netty.channel.WriteBufferWaterMark;
import io.netty.channel.epoll.Epoll;
import io.netty.channel.epoll.EpollIoHandler;
import io.netty.channel.epoll.EpollServerSocketChannel;
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.util.concurrent.DefaultThreadFactory;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GlobalEventExecutor;
import io.netty.util.internal.PlatformDependent;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.time.InstantSource;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The edge listener: accepts connections on {@code jburrow.edge.port}, dispatches each request on them by its
 * {@code Host}, hands each upgraded channel to the {@link SessionInitializer}, and each request for a tunnel to the
 * session that {@link ExchangeSessions} finds for it. Its worker event loops run every session, tunnel listener,
 * visitor connection and HTTP exchange (ADR-0008, ADR-0012).
 *
 * <p>Started once and stopped once; the composition root ties both to the application's lifecycle.
 */
public final class EdgeServer {

    private static final Logger log = LoggerFactory.getLogger(EdgeServer.class);

    /**
     * How long connections still open once every session was told to end get to close, before they are closed: a
     * client's time to complete the close, as the session closer gives it.
     */
    public static final Duration CLOSE_TIMEOUT = Duration.ofSeconds(2);

    /**
     * How long sessions whose connections were closed at the end of {@link #CLOSE_TIMEOUT} get to give back their names
     * and ports: their listeners only have to close.
     */
    public static final Duration RELEASE_GRACE = Duration.ofMillis(500);

    /** How long the event loop groups get to finish their queued work; there is no quiet period. */
    public static final Duration GROUP_SHUTDOWN_TIMEOUT = Duration.ofSeconds(5);

    private static final int BACKLOG = 1_024;

    /** The streams cut, while the sessions haven't all given back what they took. */
    private static final int UNKNOWN = -1;

    private final EdgeSettings settings;
    private final EngineDependencies deps;
    private final SessionInitializer sessions;
    private final ExchangeSessions exchangeSessions;
    private final Transport transport;
    private final ChannelGroup connections = new DefaultChannelGroup("jburrow-edge", GlobalEventExecutor.INSTANCE);

    private volatile boolean shuttingDown;
    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private volatile Channel serverChannel;
    private CompletableFuture<Void> drained;
    private CompletableFuture<Void> stopped;

    /** The event loop and server channel types of one transport. */
    private record Transport(String name, IoHandlerFactory ioHandlers, Class<? extends ServerChannel> serverChannel) {}

    /**
     * An edge that serves no HTTP exchange: every tunnel request gets the {@code TUNNEL_NOT_FOUND} page.
     *
     * @throws IllegalStateException if epoll is forced and unavailable on this host
     */
    public EdgeServer(EdgeSettings settings, EngineDependencies deps, SessionInitializer sessions) {
        this(settings, deps, sessions, ExchangeSessions.none());
    }

    /**
     * @param exchangeSessions finds the session serving a tunnel, for each request routed to one
     * @throws IllegalStateException if epoll is forced and unavailable on this host
     */
    public EdgeServer(
            EdgeSettings settings,
            EngineDependencies deps,
            SessionInitializer sessions,
            ExchangeSessions exchangeSessions) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.deps = Objects.requireNonNull(deps, "deps");
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.exchangeSessions = Objects.requireNonNull(exchangeSessions, "exchangeSessions");
        this.transport = transport(settings.transport());
    }

    private static Transport transport(EdgeTransport requested) {
        boolean epoll =
                switch (requested) {
                    case NIO -> false;
                    case AUTO -> Epoll.isAvailable();
                    case EPOLL -> {
                        if (!Epoll.isAvailable()) {
                            throw new IllegalStateException(
                                    "jburrow.edge.transport is epoll, but epoll is unavailable",
                                    Epoll.unavailabilityCause());
                        }
                        yield true;
                    }
                };
        return epoll
                ? new Transport("epoll", EpollIoHandler.newFactory(), EpollServerSocketChannel.class)
                : new Transport("nio", NioIoHandler.newFactory(), NioServerSocketChannel.class);
    }

    /**
     * Binds the listener and waits for the bind, so a port in use fails the caller with the cause. Does nothing if
     * already started.
     *
     * @throws IllegalStateException if the bind fails, or the server was stopped
     */
    public synchronized void start() {
        if (drained != null || stopped != null) {
            throw new IllegalStateException("The edge was stopped and can't be started again");
        }
        if (serverChannel != null) {
            return;
        }
        bossGroup = new MultiThreadIoEventLoopGroup(1, threads("jburrow-edge-boss"), transport.ioHandlers());
        workerGroup = new MultiThreadIoEventLoopGroup(
                settings.ioThreads(), threads("jburrow-edge-io"), transport.ioHandlers());
        ChannelFuture bind = new ServerBootstrap()
                .group(bossGroup, workerGroup)
                .channel(transport.serverChannel())
                .option(ChannelOption.SO_BACKLOG, BACKLOG)
                .option(ChannelOption.SO_REUSEADDR, true)
                .childOption(ChannelOption.TCP_NODELAY, true)
                .childOption(
                        ChannelOption.WRITE_BUFFER_WATER_MARK,
                        new WriteBufferWaterMark(settings.sessionWriteLow(), settings.sessionWriteHigh()))
                .childOption(ChannelOption.AUTO_READ, true)
                .childHandler(channelInitializer())
                .bind(settings.bind())
                .awaitUninterruptibly();
        if (!bind.isSuccess()) {
            bossGroup.shutdownGracefully(0, 0, TimeUnit.SECONDS);
            workerGroup.shutdownGracefully(0, 0, TimeUnit.SECONDS);
            throw new IllegalStateException(
                    "Could not bind the edge to " + address(settings.bind()) + ": " + bind.cause(), bind.cause());
        }
        serverChannel = bind.channel();
        log.info(
                "edge listening on {}:{} ({}, {} io threads), connect host {}, drain {} ms, reconnect spread {} ms",
                address(settings.bind()),
                boundPort(),
                transport.name(),
                settings.ioThreads(),
                settings.connectHost(),
                settings.drain().timeout().toMillis(),
                settings.drain().reconnectSpread().toMillis());
    }

    private EdgeChannelInitializer channelInitializer() {
        ControlJson json = ControlJson.create();
        UpgradeErrorResponses responses = new UpgradeErrorResponses(json, InstantSource.system());
        ConnectRequestFilter filter = new ConnectRequestFilter(
                settings.connectHost(), deps.admission(), () -> shuttingDown, deps.metrics(), responses);
        ClientAddressResolver clientAddresses = new ClientAddressResolver(settings.trustedProxies());
        ConnectPipeline connect = new ConnectPipeline(settings, clientAddresses, filter, sessions);
        EdgeErrorPage pages = new EdgeErrorPage();
        ExchangeServices exchanges = new ExchangeServices(
                deps.routes(),
                exchangeSessions,
                pages,
                json,
                settings.http().maxRequestBody(),
                () -> shuttingDown,
                deps.metrics());
        return new EdgeChannelInitializer(
                settings,
                connections,
                connect,
                clientAddresses,
                () -> new ExchangeHandler(exchanges),
                pages,
                deps.metrics());
    }

    /** The port the listener is bound to, which differs from the configured one when that is 0. */
    public int boundPort() {
        Channel channel = serverChannel;
        if (channel == null) {
            throw new IllegalStateException("The edge has not been started");
        }
        return ((InetSocketAddress) channel.localAddress()).getPort();
    }

    /** Whether the listener is bound and not shutting down. */
    public boolean isAccepting() {
        Channel channel = serverChannel;
        return !shuttingDown && channel != null && channel.isActive();
    }

    /**
     * Stops accepting: closes the listener, so the kernel refuses new connections, and refuses any upgrade still in
     * progress with {@code SERVER_SHUTTING_DOWN}.
     */
    public void beginShutdown() {
        if (shuttingDown) {
            return;
        }
        shuttingDown = true;
        Channel channel = serverChannel;
        if (channel != null) {
            log.info("edge shutting down: no new connections");
            channel.close();
        }
    }

    /**
     * Drains the edge, steps 2 to 7 of {@code docs/phase-4-staying-connected/ARCHITECTURE.md} §The drain: close the
     * listener, drain every session for up to {@code jburrow.drain.timeout}, end every session with
     * {@code SERVER_SHUTTING_DOWN}, give them {@link #CLOSE_TIMEOUT} to close and give back what they took, then close
     * every connection still open, visitors' included, giving sessions cut off that way {@link #RELEASE_GRACE}. Logs one line when it is over. The event loops
     * keep running, for {@link #stop}. Never blocks. Calling it again returns the same future.
     *
     * @return completes, never exceptionally, once every session has given back what it took, its names and ports on
     *     hold, or once the waits above have passed
     */
    public CompletableFuture<Void> drain() {
        Channel channel;
        synchronized (this) {
            if (drained != null) {
                return drained;
            }
            drained = new CompletableFuture<>();
            channel = serverChannel;
            if (channel == null) {
                drained.complete(null);
                return drained;
            }
        }
        beginShutdown();
        long startedNanos = System.nanoTime();
        Duration timeout = settings.drain().timeout();
        CompletableFuture<Void> result = drained;
        completion(channel.closeFuture())
                .thenCompose(closed ->
                        guarded("drain the sessions", () -> sessions.drain(timeout), SessionInitializer.Drained.NONE))
                .thenCompose(started -> {
                    CompletableFuture<Integer> ended = guarded("end the sessions", sessions::end, UNKNOWN);
                    return within(ended, CLOSE_TIMEOUT)
                            .thenCompose(cut -> completion(connections.close())
                                    .thenCompose(rest -> cut != UNKNOWN
                                            ? CompletableFuture.completedFuture(cut)
                                            : within(ended, RELEASE_GRACE)))
                            .thenAccept(cut -> {
                                if (cut != UNKNOWN) {
                                    deps.metrics().drainStreamsCut(cut);
                                }
                                log.info(
                                        "drain: sessions={} streams={} cut={} ms={}",
                                        started.sessions(),
                                        started.openStreams(),
                                        cut == UNKNOWN ? "unknown" : cut,
                                        Duration.ofNanos(System.nanoTime() - startedNanos)
                                                .toMillis());
                            });
                })
                .whenComplete((done, failure) -> {
                    if (failure != null) {
                        log.error("The drain failed; the event loops close what is left", failure);
                    }
                    result.complete(null);
                });
        return result;
    }

    /** {@code step}'s result, or {@link #UNKNOWN} once {@code timeout} has passed; {@code step} itself is untouched. */
    private static CompletableFuture<Integer> within(CompletableFuture<Integer> step, Duration timeout) {
        return step.copy().completeOnTimeout(UNKNOWN, timeout.toNanos(), TimeUnit.NANOSECONDS);
    }

    /**
     * Stops the edge: {@link #drain} if it hasn't been, then step 9, shutting down the event loops. Never blocks.
     * Calling it again returns the same future.
     *
     * @return completes when the event loops have terminated, or exceptionally after {@code timeout}
     */
    public CompletableFuture<Void> stop(Duration timeout) {
        Objects.requireNonNull(timeout, "timeout");
        synchronized (this) {
            if (stopped != null) {
                return stopped;
            }
            stopped = new CompletableFuture<>();
            if (serverChannel == null) {
                stopped.complete(null);
                return stopped;
            }
        }
        long startedNanos = System.nanoTime();
        CompletableFuture<Void> result = stopped;
        drain().thenCompose(done -> CompletableFuture.allOf(shutDown(bossGroup), shutDown(workerGroup)))
                .whenComplete((done, failure) -> {
                    if (failure != null) {
                        result.completeExceptionally(failure);
                    } else {
                        log.info(
                                "edge stopped in {} ms",
                                Duration.ofNanos(System.nanoTime() - startedNanos)
                                        .toMillis());
                        result.complete(null);
                    }
                });
        return result.orTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    /**
     * A failure to drain or end the sessions must not stop the rest of the shutdown, which closes their connections
     * anyway.
     */
    private static <T> CompletableFuture<T> guarded(String what, Supplier<CompletableFuture<T>> step, T otherwise) {
        try {
            return step.get().exceptionally(failure -> {
                log.error("Could not {}; their connections will be closed", what, failure);
                return otherwise;
            });
        } catch (RuntimeException e) {
            log.error("Could not {}; their connections will be closed", what, e);
            return CompletableFuture.completedFuture(otherwise);
        }
    }

    /** {@code "epoll"} or {@code "nio"}. */
    public String transportName() {
        return transport.name();
    }

    /**
     * Direct memory held by Netty's buffers. Netty counts it itself only when it manages direct memory without the
     * JDK's cleaner; otherwise the default allocator's own count is the closest measure.
     */
    public long usedDirectMemory() {
        long used = PlatformDependent.usedDirectMemory();
        if (used >= 0) {
            return used;
        }
        return ByteBufAllocator.DEFAULT instanceof ByteBufAllocatorMetricProvider metered
                ? metered.metric().usedDirectMemory()
                : -1;
    }

    /** The most direct memory Netty's buffers may hold, in bytes: the budget the edge's settings are checked against. */
    public long maxDirectMemory() {
        return PlatformDependent.maxDirectMemory();
    }

    /** Connections accepted and not yet closed. A connection still in the kernel's accept queue isn't counted. */
    int openConnections() {
        return connections.size();
    }

    /** The accepted connections not yet closed, for tests that inspect their options. */
    ChannelGroup connections() {
        return connections;
    }

    private static CompletableFuture<Void> shutDown(EventLoopGroup group) {
        return completion(group.shutdownGracefully(0, GROUP_SHUTDOWN_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS));
    }

    /** Completes when {@code future} does, successfully or not: a close that failed still leaves nothing to wait for. */
    private static CompletableFuture<Void> completion(Future<?> future) {
        CompletableFuture<Void> done = new CompletableFuture<>();
        future.addListener(finished -> done.complete(null));
        return done;
    }

    private static String address(InetSocketAddress bind) {
        return bind.getAddress().getHostAddress();
    }

    private static ThreadFactory threads(String prefix) {
        AtomicInteger next = new AtomicInteger(1);
        return new DefaultThreadFactory(prefix) {
            @Override
            protected Thread newThread(Runnable task, String name) {
                return super.newThread(task, prefix + "-" + next.getAndIncrement());
            }
        };
    }
}
