package io.jburrow.client.tunnel.adapter.netty;

import io.jburrow.client.ClientSettings;
import io.jburrow.client.JBurrowClient;
import io.jburrow.client.TunnelHandle;
import io.jburrow.client.TunnelStartException;
import io.jburrow.protocol.adapter.jackson.ControlJson;
import io.jburrow.protocol.adapter.netty.FrameCodec;
import io.jburrow.protocol.adapter.netty.WebSocketFrameDecoder;
import io.jburrow.protocol.adapter.netty.WebSocketFrameEncoder;
import io.jburrow.protocol.wire.FrameLimits;
import io.jburrow.protocol.wire.ProtocolVersion;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.ChannelFactory;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoop;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.WriteBufferWaterMark;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.websocketx.WebSocketClientProtocolConfig;
import io.netty.handler.codec.http.websocketx.WebSocketClientProtocolHandler;
import io.netty.handler.codec.http.websocketx.WebSocketFrameAggregator;
import io.netty.util.concurrent.FastThreadLocalThread;
import io.netty.util.concurrent.Future;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;
import javax.net.ssl.SSLException;

/**
 * The client on Netty: one NIO event loop on a daemon thread, which carries the control connection, every local
 * connection and every timer of the session, so the session's state is touched by that one thread only. NIO on every
 * platform, never a native transport, so the client behaves the same everywhere and under native-image.
 *
 * <p>Blocking work, resolving host names, runs on virtual threads and never on the loop.
 *
 * <p>The control connection's pipeline:
 *
 * <pre>
 * SslHandler                          wss only; {@link ClientTls}
 * HttpClientCodec                     initial line 4,096, headers 8,192
 * HttpObjectAggregator                65,536: bounds a refusal's body
 * UpgradeFailures                     reads a refused upgrade's JSON before Netty's handshaker drops it
 * WebSocketClientProtocolHandler      the upgrade; masking, no extensions, no Origin
 * WebSocketFrameAggregator            whole messages of at most 65,546 bytes
 * WebSocketFrameDecoder / Encoder     binary message to and from a protocol frame; what {@code http} introduces only
 *                                     once the server's {@code HELLO} has listed it
 * ClientLivenessHandler               PING, PONG and the idle timeout
 * ControlFrameHandler                 the session
 * </pre>
 */
public final class NettyTunnelClient implements JBurrowClient {

    static final String IO_THREAD_NAME = "jburrow-client-io";

    private static final int CONNECT_TIMEOUT_MILLIS = 10_000;
    private static final WriteBufferWaterMark CONTROL_WRITE_BUFFER = new WriteBufferWaterMark(128 * 1024, 512 * 1024);
    private static final int MAX_INITIAL_LINE_LENGTH = 4_096;
    private static final int MAX_HEADER_SIZE = 8_192;
    private static final int MAX_CHUNK_SIZE = 8_192;
    private static final int MAX_UPGRADE_RESPONSE = 65_536;
    private static final Duration SHUTDOWN_TIMEOUT = Duration.ofSeconds(2);

    private final ClientSettings settings;
    private final EventLoopGroup group;
    private final EventLoop loop;
    private final ExecutorService blocking;
    private final Dialer dialer;
    private final ControlJson json = ControlJson.create();
    private final LongSupplier nanoTime = System::nanoTime;
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();

    private volatile ControlConnection connection;

    public NettyTunnelClient(ClientSettings settings) {
        this(settings, HostResolver.SYSTEM);
    }

    NettyTunnelClient(ClientSettings settings, HostResolver resolver) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.group = new MultiThreadIoEventLoopGroup(
                1,
                runnable -> {
                    Thread thread = new FastThreadLocalThread(runnable, IO_THREAD_NAME);
                    thread.setDaemon(true);
                    return thread;
                },
                NioIoHandler.newFactory());
        this.loop = group.next();
        this.blocking = Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name("jburrow-client-resolver-", 0).factory());
        this.dialer = new Dialer(resolver, blocking);
    }

    @Override
    public TunnelHandle start() throws TunnelStartException {
        if (closed.get()) {
            throw new IllegalStateException("The client is closed");
        }
        if (!started.compareAndSet(false, true)) {
            throw new IllegalStateException("A client is started once; build a new one for a new session");
        }
        ClientTls tls = settings.tls() ? tls() : null;
        ControlConnection session = new ControlConnection(settings);
        connection = session;
        Bootstrap bootstrap = new Bootstrap()
                .group(loop)
                .channelFactory((ChannelFactory<NioSocketChannel>) NioSocketChannel::new)
                .option(ChannelOption.TCP_NODELAY, true)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, CONNECT_TIMEOUT_MILLIS)
                .option(ChannelOption.WRITE_BUFFER_WATER_MARK, CONTROL_WRITE_BUFFER)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel channel) {
                        initControlPipeline(channel, session, tls);
                    }
                });
        dialer.dial(bootstrap, loop, settings.host(), settings.port()).addListener(dialed -> {
            if (!dialed.isSuccess()) {
                session.startFailed(UpgradeFailures.fromCause(dialed.cause()));
            }
        });
        return session.awaitStart();
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        boolean onLoop = loop.inEventLoop();
        ControlConnection session = connection;
        if (session != null) {
            session.close();
            if (!onLoop) {
                session.awaitClosed(ControlConnection.CLOSE_TIMEOUT.plusMillis(500));
            }
        }
        Future<?> shutdown = group.shutdownGracefully(0, SHUTDOWN_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        blocking.shutdownNow();
        if (onLoop) {
            return;
        }
        shutdown.awaitUninterruptibly(SHUTDOWN_TIMEOUT.multipliedBy(2).toMillis());
        if (session != null) {
            session.abandon();
        }
    }

    /** Whether the client has stopped reading the control connection, which flow control never does. */
    boolean isControlReadPaused() throws InterruptedException {
        ControlConnection session = connection;
        if (session == null) {
            return false;
        }
        try {
            return loop.submit(session::isReadPaused).get(5, TimeUnit.SECONDS);
        } catch (ExecutionException | TimeoutException e) {
            throw new IllegalStateException("Couldn't read the control connection's state", e);
        }
    }

    private ClientTls tls() throws TunnelStartException {
        try {
            return ClientTls.forServer(settings.host(), settings.port(), settings.trustManager());
        } catch (SSLException e) {
            throw new TunnelStartException(
                    TunnelStartException.Kind.TLS_FAILED,
                    "TLS couldn't be set up: " + e.getMessage(),
                    Optional.empty(),
                    List.of(),
                    e);
        }
    }

    private void initControlPipeline(SocketChannel channel, ControlConnection session, ClientTls tls) {
        ClientLivenessHandler liveness = new ClientLivenessHandler(session.session(), session, nanoTime);
        LocalConnector streams = new LocalConnector(session, channel, dialer, json, nanoTime);
        FrameCodec codec = new FrameCodec(FrameCodec.Role.CLIENT, json, session.session()::httpEnabled);
        if (tls != null) {
            channel.pipeline().addLast("tls", tls.newHandler(channel.alloc()));
        }
        channel.pipeline()
                .addLast("http", new HttpClientCodec(MAX_INITIAL_LINE_LENGTH, MAX_HEADER_SIZE, MAX_CHUNK_SIZE))
                .addLast("http-aggregator", new HttpObjectAggregator(MAX_UPGRADE_RESPONSE))
                .addLast("upgrade-failures", new UpgradeFailures(session, json))
                .addLast("websocket", new WebSocketClientProtocolHandler(webSocketConfig()))
                .addLast("message-aggregator", new WebSocketFrameAggregator(FrameLimits.MAX_MESSAGE_LENGTH))
                .addLast("frame-decoder", new WebSocketFrameDecoder(codec))
                .addLast("frame-encoder", new WebSocketFrameEncoder(codec))
                .addLast("liveness", liveness)
                .addLast("session", new ControlFrameHandler(session, liveness, streams));
    }

    /**
     * The upgrade request, {@code docs/protocol.md} §3.3: the subprotocol, no {@code Origin}, no extensions, and a
     * relative request target. Close frames are passed on rather than handled, and none is sent on close, so the
     * session decides every close itself (§8.6).
     */
    private WebSocketClientProtocolConfig webSocketConfig() {
        return WebSocketClientProtocolConfig.newBuilder()
                .webSocketUri(settings.server())
                .subprotocol(ProtocolVersion.CURRENT)
                .allowExtensions(false)
                .performMasking(true)
                .maxFramePayloadLength(FrameLimits.MAX_MESSAGE_LENGTH)
                .handshakeTimeoutMillis(settings.handshakeTimeout().toMillis())
                .generateOriginHeader(false)
                .absoluteUpgradeUrl(false)
                .dropPongFrames(true)
                .handleCloseFrames(false)
                .sendCloseFrame(null)
                .customHeaders(new DefaultHttpHeaders().set(HttpHeaderNames.USER_AGENT, settings.userAgent()))
                .build();
    }
}
