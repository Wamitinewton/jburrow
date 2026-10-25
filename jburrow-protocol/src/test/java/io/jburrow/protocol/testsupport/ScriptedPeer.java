package io.jburrow.protocol.testsupport;

import io.jburrow.protocol.adapter.jackson.ControlJson;
import io.jburrow.protocol.adapter.netty.Frame;
import io.jburrow.protocol.adapter.netty.FrameCodec;
import io.jburrow.protocol.adapter.netty.WebSocketFrameDecoder;
import io.jburrow.protocol.adapter.netty.WebSocketFrameEncoder;
import io.jburrow.protocol.control.ErrorBody;
import io.jburrow.protocol.wire.ErrorCode;
import io.jburrow.protocol.wire.FrameLimits;
import io.jburrow.protocol.wire.ProtocolException;
import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketClientProtocolConfig;
import io.netty.handler.codec.http.websocketx.WebSocketClientProtocolHandler;
import io.netty.handler.codec.http.websocketx.WebSocketCloseStatus;
import io.netty.handler.codec.http.websocketx.WebSocketFrameAggregator;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolConfig;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import io.netty.handler.ssl.ApplicationProtocolNames;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslHandler;
import io.netty.util.ReferenceCountUtil;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.net.ssl.ExtendedSSLSession;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SNIServerName;
import javax.net.ssl.SSLEngine;

/**
 * A real WebSocket peer for byte-path tests, on the real codec, that plays either side and sends whatever a test
 * scripts, including bytes no correct peer would send. The server is tested against a scripted client and the client
 * against a scripted server, so neither side's own implementation can hide a bug in the other.
 *
 * <p>It answers every {@code PING} with the matching {@code PONG} and drops {@code PONG}s, as a healthy peer does,
 * until {@link #silence()}. Every other frame waits for an {@code expect} call. A frame a test takes is the test's to
 * release; whatever is left when the peer closes, it releases.
 *
 * <p>Each peer has its own event loop. A listening peer accepts one connection and refuses any after it.
 */
public final class ScriptedPeer implements AutoCloseable {

    public static final String CONNECT_PATH = "/v1/connect";

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration CLOSE_TIMEOUT = Duration.ofSeconds(2);
    private static final int MAX_HTTP_MESSAGE = 8_192;

    private final EventLoopGroup group;
    private final CompletableFuture<Channel> connection = new CompletableFuture<>();
    private final CompletableFuture<HttpHeaders> upgradeRequest = new CompletableFuture<>();
    private final CompletableFuture<TlsHello> tlsHello = new CompletableFuture<>();
    private final BlockingQueue<Object> received = new LinkedBlockingQueue<>();
    private final AtomicBoolean closeRecorded = new AtomicBoolean();
    private volatile boolean silenced;
    private volatile boolean closing;
    private volatile Channel listener;
    private volatile List<String> alpnOffered = List.of();

    /** A message the codec refused. */
    private record Rejected(ProtocolException error) {}

    /** The WebSocket connection ended, with the peer's close status, or 1006 if it closed without one. */
    private record Closed(WebSocketCloseStatus status) {}

    /**
     * What a TLS client asked for in its ClientHello, as a peer from {@link #listenTls} saw it.
     *
     * @param serverNames the host names sent as SNI
     * @param applicationProtocols the ALPN protocols offered, in the client's order; empty if it offered none
     */
    public record TlsHello(List<String> serverNames, List<String> applicationProtocols) {}

    private ScriptedPeer() {
        this.group = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
    }

    /** Connects to a server at a {@code ws://} URI as a client, offering {@code subprotocol}. Masks its frames. */
    public static ScriptedPeer connect(URI uri, String subprotocol) {
        if (!"ws".equals(uri.getScheme())) {
            throw new IllegalArgumentException("A scripted client speaks ws:// only: " + uri);
        }
        ScriptedPeer peer = new ScriptedPeer();
        FrameCodec codec = new FrameCodec(FrameCodec.Role.CLIENT, ControlJson.create());
        WebSocketClientProtocolConfig config = WebSocketClientProtocolConfig.newBuilder()
                .webSocketUri(uri)
                .subprotocol(subprotocol)
                .allowExtensions(false)
                .generateOriginHeader(false)
                .maxFramePayloadLength(FrameLimits.MAX_MESSAGE_LENGTH)
                .handleCloseFrames(false)
                .handshakeTimeoutMillis(CONNECT_TIMEOUT.toMillis())
                .build();
        new Bootstrap()
                .group(peer.group)
                .channel(NioSocketChannel.class)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ch.pipeline()
                                .addLast(new HttpClientCodec())
                                .addLast(new HttpObjectAggregator(MAX_HTTP_MESSAGE))
                                .addLast(new WebSocketClientProtocolHandler(config))
                                .addLast(peer.frameHandlers(codec));
                    }
                })
                .connect(uri.getHost(), uri.getPort())
                .addListener((ChannelFuture f) -> {
                    if (!f.isSuccess()) {
                        peer.connection.completeExceptionally(f.cause());
                    }
                });
        try {
            peer.awaitConnection();
        } catch (RuntimeException e) {
            peer.close();
            throw e;
        }
        return peer;
    }

    /** Listens on loopback as a server, accepting {@code subprotocol}. Port 0 picks an ephemeral port. */
    public static ScriptedPeer listen(int port, String subprotocol) {
        return listen(port, subprotocol, null);
    }

    /**
     * Listens on loopback as a server behind TLS with {@code serverContext}, accepting {@code subprotocol}. The peer
     * negotiates ALPN itself, choosing {@code http/1.1} when it is offered, and records what the client asked for
     * ({@link #tlsHello}), so {@code serverContext} must be a JDK provider context without ALPN configuration.
     */
    public static ScriptedPeer listenTls(int port, SslContext serverContext, String subprotocol) {
        return listen(port, subprotocol, Objects.requireNonNull(serverContext, "serverContext"));
    }

    private static ScriptedPeer listen(int port, String subprotocol, SslContext tls) {
        ScriptedPeer peer = new ScriptedPeer();
        FrameCodec codec = new FrameCodec(FrameCodec.Role.SERVER, ControlJson.create());
        WebSocketServerProtocolConfig config = WebSocketServerProtocolConfig.newBuilder()
                .websocketPath(CONNECT_PATH)
                .subprotocols(subprotocol)
                .allowExtensions(false)
                .maxFramePayloadLength(FrameLimits.MAX_MESSAGE_LENGTH)
                .handleCloseFrames(false)
                .handshakeTimeoutMillis(CONNECT_TIMEOUT.toMillis())
                .build();
        ChannelFuture bind = new ServerBootstrap()
                .group(peer.group)
                .channel(NioServerSocketChannel.class)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        if (peer.connection.isDone()) {
                            ch.close();
                            return;
                        }
                        if (tls != null) {
                            ch.pipeline().addLast(peer.tlsHandler(tls, ch));
                        }
                        ch.pipeline()
                                .addLast(new HttpServerCodec())
                                .addLast(new HttpObjectAggregator(MAX_HTTP_MESSAGE))
                                .addLast(new WebSocketServerProtocolHandler(config))
                                .addLast(peer.frameHandlers(codec));
                    }
                })
                .bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), port))
                .syncUninterruptibly();
        peer.listener = bind.channel();
        return peer;
    }

    /** The port a listening peer is bound to. */
    public int port() {
        return ((InetSocketAddress) requireListener().localAddress()).getPort();
    }

    /** The URI a client connects to a listening peer on. */
    public URI uri() {
        return URI.create("ws://" + InetAddress.getLoopbackAddress().getHostAddress() + ":" + port() + CONNECT_PATH);
    }

    /** Sends {@code frame} through the real codec. Takes over the frame's buffer. */
    public void send(Frame frame) {
        Objects.requireNonNull(frame, "frame");
        Channel channel;
        try {
            channel = awaitConnection();
        } catch (RuntimeException e) {
            frame.release();
            throw e;
        }
        write(channel, frame);
    }

    /** Sends arbitrary bytes, in the conformance vectors' hex notation, as one binary WebSocket message. */
    public void sendRaw(String hex) {
        byte[] bytes = Hex.decode(hex);
        Channel channel = awaitConnection();
        write(
                channel,
                new BinaryWebSocketFrame(channel.alloc().buffer(bytes.length).writeBytes(bytes)));
    }

    public void sendText(String text) {
        write(awaitConnection(), new TextWebSocketFrame(text));
    }

    /**
     * The next frame, which must be a {@code type}. Anything else, or nothing within {@code timeout}, fails the test.
     * The caller owns the returned frame.
     */
    public <F extends Frame> F expect(Class<F> type, Duration timeout) {
        return next(type, timeout);
    }

    /** The next frame must be an {@code ERROR} with {@code code}, and the connection must then close. */
    public ErrorBody expectError(ErrorCode code, Duration timeout) {
        ErrorBody body = expect(Frame.Error.class, timeout).body();
        if (body.code() != code) {
            throw new AssertionError("Expected ERROR " + code + ", got ERROR " + body.code());
        }
        expectClosed(timeout);
        return body;
    }

    /** The connection must close next. Returns the peer's close status, or 1006 if it sent none. */
    public WebSocketCloseStatus expectClosed(Duration timeout) {
        return next(Closed.class, timeout).status();
    }

    /** The next message from the peer must be one the codec refused. Returns the codec's error. */
    public ProtocolException expectRejected(Duration timeout) {
        return next(Rejected.class, timeout).error();
    }

    /** The headers of the client's upgrade request, once the upgrade has completed. */
    public HttpHeaders upgradeRequestHeaders() {
        awaitConnection();
        return upgradeRequest.getNow(new DefaultHttpHeaders());
    }

    /** What the client sent in its TLS ClientHello; only for a peer from {@link #listenTls}. */
    public TlsHello tlsHello() {
        try {
            return tlsHello.get(CONNECT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted waiting for the TLS handshake", e);
        } catch (ExecutionException e) {
            throw new IllegalStateException("The TLS handshake failed", e.getCause());
        } catch (TimeoutException e) {
            throw new IllegalStateException("No TLS handshake within " + CONNECT_TIMEOUT, e);
        }
    }

    /** Closes the TCP connection without a WebSocket close, as a crashed or vanished peer would. */
    public void dropConnection() {
        awaitConnection().close().awaitUninterruptibly();
    }

    public void pauseReading() {
        awaitConnection().config().setAutoRead(false);
    }

    public void resumeReading() {
        awaitConnection().config().setAutoRead(true);
    }

    /** Stops answering {@code PING} and starts queueing {@code PING} and {@code PONG} for {@link #expect}. */
    public void silence() {
        silenced = true;
    }

    /** Sends a normal WebSocket close if connected, stops the peer and releases every frame no test took. */
    @Override
    public void close() {
        closing = true;
        Channel channel = connection.isCompletedExceptionally() ? null : connection.getNow(null);
        if (channel != null && channel.isActive()) {
            channel.writeAndFlush(new CloseWebSocketFrame(WebSocketCloseStatus.NORMAL_CLOSURE))
                    .addListener(f -> channel.close());
            channel.closeFuture().awaitUninterruptibly(CLOSE_TIMEOUT.toMillis());
        }
        if (listener != null) {
            listener.close().awaitUninterruptibly();
        }
        group.shutdownGracefully(0, CLOSE_TIMEOUT.toSeconds(), TimeUnit.SECONDS).syncUninterruptibly();
        for (Object event = received.poll(); event != null; event = received.poll()) {
            ReferenceCountUtil.release(event);
        }
    }

    private SslHandler tlsHandler(SslContext tls, SocketChannel ch) {
        SSLEngine engine = tls.newEngine(ch.alloc());
        engine.setHandshakeApplicationProtocolSelector((handshaking, offered) -> {
            alpnOffered = List.copyOf(offered);
            return offered.contains(ApplicationProtocolNames.HTTP_1_1) ? ApplicationProtocolNames.HTTP_1_1 : null;
        });
        SslHandler handler = new SslHandler(engine);
        handler.handshakeFuture().addListener(done -> {
            if (!done.isSuccess()) {
                tlsHello.completeExceptionally(done.cause());
                return;
            }
            List<String> serverNames = engine.getSession() instanceof ExtendedSSLSession session
                    ? session.getRequestedServerNames().stream()
                            .map(ScriptedPeer::hostName)
                            .toList()
                    : List.of();
            tlsHello.complete(new TlsHello(serverNames, alpnOffered));
        });
        return handler;
    }

    private static String hostName(SNIServerName name) {
        return name instanceof SNIHostName host ? host.getAsciiName() : name.toString();
    }

    private ChannelHandler[] frameHandlers(FrameCodec codec) {
        return new ChannelHandler[] {
            new WebSocketFrameAggregator(FrameLimits.MAX_MESSAGE_LENGTH),
            new WebSocketFrameDecoder(codec),
            new WebSocketFrameEncoder(codec),
            new PeerHandler()
        };
    }

    private Channel awaitConnection() {
        try {
            return connection.get(CONNECT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted waiting for the WebSocket connection", e);
        } catch (ExecutionException e) {
            throw new IllegalStateException("The WebSocket connection failed", e.getCause());
        } catch (TimeoutException e) {
            throw new IllegalStateException("No WebSocket connection within " + CONNECT_TIMEOUT, e);
        }
    }

    private Channel requireListener() {
        if (listener == null) {
            throw new IllegalStateException("Only a listening peer has a port");
        }
        return listener;
    }

    private static void write(Channel channel, Object message) {
        ChannelFuture write = channel.writeAndFlush(message).awaitUninterruptibly();
        if (!write.isSuccess()) {
            throw new IllegalStateException("Write failed", write.cause());
        }
    }

    private <T> T next(Class<T> type, Duration timeout) {
        Object event;
        try {
            event = received.poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted waiting for " + type.getSimpleName(), e);
        }
        if (event == null) {
            throw new AssertionError("Expected " + type.getSimpleName() + " within " + timeout + ", got nothing");
        }
        if (!type.isInstance(event)) {
            String description = describe(event);
            ReferenceCountUtil.release(event);
            throw new AssertionError("Expected " + type.getSimpleName() + ", got " + description);
        }
        return type.cast(event);
    }

    private static String describe(Object event) {
        return switch (event) {
            case Rejected rejected ->
                "a message the codec refused: " + rejected.error().code() + " "
                        + rejected.error().getMessage();
            case Closed closed -> "the connection closing with " + closed.status();
            default -> event.toString();
        };
    }

    private void recordClosed(WebSocketCloseStatus status) {
        if (closeRecorded.compareAndSet(false, true)) {
            received.add(new Closed(status));
        }
    }

    private final class PeerHandler extends ChannelInboundHandlerAdapter {

        @Override
        public void userEventTriggered(ChannelHandlerContext ctx, Object event) throws Exception {
            if (event == WebSocketClientProtocolHandler.ClientHandshakeStateEvent.HANDSHAKE_COMPLETE
                    || event instanceof WebSocketServerProtocolHandler.HandshakeComplete) {
                if (event instanceof WebSocketServerProtocolHandler.HandshakeComplete upgrade) {
                    upgradeRequest.complete(new DefaultHttpHeaders().set(upgrade.requestHeaders()));
                }
                connection.complete(ctx.channel());
            }
            super.userEventTriggered(ctx, event);
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            switch (msg) {
                case Frame.Ping ping when !silenced -> ctx.writeAndFlush(new Frame.Pong(ping.opaque()));
                case Frame.Pong ignored when !silenced -> {}
                case Frame frame -> received.add(frame);
                case CloseWebSocketFrame close -> {
                    int code = close.statusCode();
                    close.release();
                    recordClosed(code < 0 ? WebSocketCloseStatus.EMPTY : WebSocketCloseStatus.valueOf(code));
                    if (!closing) {
                        ctx.writeAndFlush(new CloseWebSocketFrame()).addListener(f -> ctx.close());
                    }
                }
                default -> ReferenceCountUtil.release(msg);
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            if (cause instanceof ProtocolException rejected) {
                received.add(new Rejected(rejected));
                return;
            }
            if (!connection.completeExceptionally(cause)) {
                received.add(new AssertionError("The scripted peer's pipeline failed", cause));
            }
            ctx.close();
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) throws Exception {
            connection.completeExceptionally(new IllegalStateException("Connection closed before the handshake"));
            recordClosed(WebSocketCloseStatus.ABNORMAL_CLOSURE);
            super.channelInactive(ctx);
        }
    }
}
