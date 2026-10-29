package io.jburrow.protocol.testsupport;

import io.jburrow.protocol.adapter.jackson.ControlJson;
import io.jburrow.protocol.adapter.netty.Frame;
import io.jburrow.protocol.adapter.netty.FrameCodec;
import io.jburrow.protocol.adapter.netty.WebSocketFrameDecoder;
import io.jburrow.protocol.adapter.netty.WebSocketFrameEncoder;
import io.jburrow.protocol.control.ErrorBody;
import io.jburrow.protocol.control.Windows;
import io.jburrow.protocol.stream.HttpExchangeRules;
import io.jburrow.protocol.wire.Capability;
import io.jburrow.protocol.wire.ErrorCode;
import io.jburrow.protocol.wire.FrameLimits;
import io.jburrow.protocol.wire.ProtocolException;
import io.jburrow.protocol.wire.StreamIds;
import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
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
 * <p>It polices the windows it announces. It keeps the implementation's credit towards it as a correct receiver would,
 * and the first {@code DATA} past its stream's credit or the session's fails the test: the next call on the peer
 * throws, and {@link #close()} throws if nothing else did. The windows are the ones its first {@code HELLO} carries, or
 * the reference windows of its role ({@link #CLIENT_WINDOWS}, {@link #SERVER_WINDOWS}) until it sends one. It returns
 * no credit unless a test asks, with {@link #grant}, {@link #grantSession} or {@link #autoGrant()}. What it sends is
 * never checked, so a test can break any window it likes.
 *
 * <p>It also keeps its own credit towards the implementation, from the windows in the implementation's {@code HELLO}
 * and the {@code WINDOW_UPDATE}s it receives, so {@link #sendData} can send as a correct sender would, waiting for
 * credit. Every {@code DATA} it sends spends that credit, however it was sent.
 *
 * <p>It negotiates capabilities as its {@code HELLO}s say: a listening peer has {@code http} once it sends a
 * {@code HELLO} listing it, a connecting peer once the implementation's {@code HELLO} lists it and its own offered it.
 * Until then its codec refuses {@code HEADERS} and everything else {@code http} introduces, in both directions, as a
 * correct peer would; {@link #sendRaw} still sends anything.
 *
 * <p>It also polices HTTP streams. It holds an {@link HttpExchangeRules} for each stream whose {@code OPEN} carried a
 * request, and the first frame from the implementation that breaks one of the stream's rules (request {@code DATA} past
 * its {@code content-length}, a second final {@code HEADERS}, a response that ends short) fails the test as an overrun
 * does. A head that breaks a field rule never gets that far: the codec refuses it, and the next {@code expect} fails.
 *
 * <p>Each peer has its own event loop. A listening peer accepts one connection and refuses any after it.
 */
public final class ScriptedPeer implements AutoCloseable {

    public static final String CONNECT_PATH = "/v1/connect";

    /** The reference client's receive windows: 256 KiB per stream, 1 MiB for the session. */
    public static final Windows CLIENT_WINDOWS = new Windows(256 * 1024, 1024 * 1024);

    /** The reference server's receive windows: 256 KiB per stream, 512 KiB for the session. */
    public static final Windows SERVER_WINDOWS = new Windows(256 * 1024, 512 * 1024);

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration CLOSE_TIMEOUT = Duration.ofSeconds(2);
    private static final int MAX_HTTP_MESSAGE = 8_192;

    private final EventLoopGroup group;
    private final CompletableFuture<Channel> connection = new CompletableFuture<>();
    private final CompletableFuture<HttpHeaders> upgradeRequest = new CompletableFuture<>();
    private final CompletableFuture<TlsHello> tlsHello = new CompletableFuture<>();
    private final BlockingQueue<Object> received = new LinkedBlockingQueue<>();
    private final AtomicBoolean closeRecorded = new AtomicBoolean();
    private final FrameCodec.Role role;
    private final Ledger ledger;
    private final SendCredit sendCredit;
    private final Map<Integer, HttpExchangeRules> exchanges = new ConcurrentHashMap<>();
    private final Set<Integer> tcpStreams = ConcurrentHashMap.newKeySet();
    private volatile boolean httpOffered;
    private volatile boolean http;
    private volatile AssertionError violation;
    private volatile boolean violationReported;
    private volatile boolean autoGrant;
    private volatile boolean ignoreWindowUpdates;
    private volatile boolean silenced;
    private volatile boolean closing;
    private volatile Channel listener;
    private volatile List<String> alpnOffered = List.of();

    /** A message the codec refused. */
    private record Rejected(ProtocolException error) {}

    /** The WebSocket connection ended, with the peer's close status, or 1006 if it closed without one. */
    private record Closed(WebSocketCloseStatus status) {}

    /** Wakes a test waiting in {@link #expect} once the implementation has broken a rule this peer polices. */
    private record Violation() {}

    /**
     * What a TLS client asked for in its ClientHello, as a peer from {@link #listenTls} saw it.
     *
     * @param serverNames the host names sent as SNI
     * @param applicationProtocols the ALPN protocols offered, in the client's order; empty if it offered none
     */
    public record TlsHello(List<String> serverNames, List<String> applicationProtocols) {}

    private ScriptedPeer(FrameCodec.Role role, Windows windows, Windows peerWindows) {
        this.role = role;
        this.group = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
        this.ledger = new Ledger(windows);
        this.sendCredit = new SendCredit(peerWindows);
    }

    /** Connects to a server at a {@code ws://} URI as a client, offering {@code subprotocol}. Masks its frames. */
    public static ScriptedPeer connect(URI uri, String subprotocol) {
        if (!"ws".equals(uri.getScheme())) {
            throw new IllegalArgumentException("A scripted client speaks ws:// only: " + uri);
        }
        ScriptedPeer peer = new ScriptedPeer(FrameCodec.Role.CLIENT, CLIENT_WINDOWS, SERVER_WINDOWS);
        FrameCodec codec = new FrameCodec(FrameCodec.Role.CLIENT, ControlJson.create(), peer::httpNegotiated);
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
        ScriptedPeer peer = new ScriptedPeer(FrameCodec.Role.SERVER, SERVER_WINDOWS, CLIENT_WINDOWS);
        FrameCodec codec = new FrameCodec(FrameCodec.Role.SERVER, ControlJson.create(), peer::httpNegotiated);
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

    /** Whether this session has the {@code http} capability, by the {@code HELLO}s sent and received so far. */
    public boolean httpNegotiated() {
        return http;
    }

    /**
     * Sends {@code frame} through the real codec. Takes over the frame's buffer. The first {@code HELLO} sets the
     * windows this peer polices and the capabilities it offers or answers with; a {@code WINDOW_UPDATE} sent this way
     * grants nothing, so a test can send one the implementation must refuse without changing the credit the peer
     * expects it to hold. An {@code OPEN} with a request starts policing that HTTP stream's response.
     */
    public void send(Frame frame) {
        Objects.requireNonNull(frame, "frame");
        Channel channel;
        try {
            failOnViolation();
            channel = awaitConnection();
        } catch (RuntimeException | AssertionError e) {
            frame.release();
            throw e;
        }
        switch (frame) {
            case Frame.Hello hello -> {
                ledger.announce(hello.message().windows());
                httpOffered = offersHttp(hello.message().capabilities());
            }
            case Frame.ServerHello hello -> {
                ledger.announce(hello.message().windows());
                http = offersHttp(hello.message().capabilities());
            }
            case Frame.Open open
            when open.message().request() != null ->
                exchanges.put(
                        open.streamId(), new HttpExchangeRules(open.message().request()));
            case Frame.Open open -> tcpStreams.add(open.streamId());
            case Frame.Close close
            when close.reset().isPresent() -> {
                ledger.finish(close.streamId());
                exchanges.remove(close.streamId());
            }
            case Frame.Data data ->
                sendCredit.spend(data.streamId(), data.payload().readableBytes());
            default -> {}
        }
        write(channel, frame);
    }

    /**
     * Sends {@code DATA} as a correct sender does: once the implementation has given this peer credit for all of it, on
     * the stream and the session, waiting up to {@code timeout}. Takes over {@code payload}.
     */
    public void sendData(int streamId, ByteBuf payload, Duration timeout) {
        try {
            failOnViolation();
            sendCredit.await(streamId, payload.readableBytes(), timeout);
        } catch (RuntimeException | AssertionError e) {
            payload.release();
            throw e;
        }
        send(new Frame.Data(streamId, payload));
    }

    /**
     * From now on, {@code WINDOW_UPDATE}s from the implementation only add to this peer's credit, and no longer wait
     * for {@link #expect}: for a test that moves data both ways and reads {@code DATA} as it comes.
     */
    public void ignoreWindowUpdates() {
        ignoreWindowUpdates = true;
    }

    /** What this peer may still send on {@code streamId}, by the implementation's grants so far. */
    public long peerStreamCredit(int streamId) {
        return sendCredit.credit(streamId);
    }

    /** What this peer may still send on all streams together, by the implementation's grants so far. */
    public long peerSessionCredit() {
        return sendCredit.credit(StreamIds.CONTROL);
    }

    /** Returns {@code increment} of credit on data stream {@code streamId}, with a {@code WINDOW_UPDATE}. */
    public void grant(int streamId, int increment) {
        if (streamId == StreamIds.CONTROL) {
            throw new IllegalArgumentException("Session credit goes back with grantSession");
        }
        sendGrant(new Frame.WindowUpdate(streamId, increment));
    }

    /** Returns {@code increment} of session credit, with a {@code WINDOW_UPDATE} on stream 0. */
    public void grantSession(int increment) {
        sendGrant(new Frame.WindowUpdate(StreamIds.CONTROL, increment));
    }

    /**
     * Returns credit from now on as a correct receiver does: once the bytes received and not yet returned reach half a
     * window, on its stream (until the implementation closes it) and on the session. Every byte received counts as
     * released at once, so credit already owed goes back now.
     */
    public void autoGrant() {
        failOnViolation();
        autoGrant = true;
        Channel channel = awaitConnection();
        for (Frame.WindowUpdate update : ledger.due()) {
            write(channel, update);
        }
    }

    /** Stops returning credit, which is also how a peer starts. */
    public void withholdCredit() {
        autoGrant = false;
    }

    /** The bytes of {@code DATA} the implementation has sent this peer on {@code streamId}. */
    public long dataReceived(int streamId) {
        return ledger.received(streamId);
    }

    /** The bytes of {@code DATA} the implementation has sent this peer on every stream together. */
    public long dataReceived() {
        return ledger.received(StreamIds.CONTROL);
    }

    /** What the implementation may still send on {@code streamId}, in this peer's view. */
    public long streamCredit(int streamId) {
        return ledger.credit(streamId);
    }

    /** What the implementation may still send on all streams together, in this peer's view. */
    public long sessionCredit() {
        return ledger.credit(StreamIds.CONTROL);
    }

    private void sendGrant(Frame.WindowUpdate update) {
        failOnViolation();
        Channel channel = awaitConnection();
        ledger.grant(update.streamId(), update.increment());
        write(channel, update);
    }

    /** Sends arbitrary bytes, in the conformance vectors' hex notation, as one binary WebSocket message. */
    public void sendRaw(String hex) {
        byte[] bytes = Hex.decode(hex);
        failOnViolation();
        Channel channel = awaitConnection();
        write(
                channel,
                new BinaryWebSocketFrame(channel.alloc().buffer(bytes.length).writeBytes(bytes)));
    }

    public void sendText(String text) {
        failOnViolation();
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

    /** Stops reading the connection. Takes effect on the peer's event loop before this returns. */
    public void pauseReading() {
        Channel channel = awaitConnection();
        channel.eventLoop().submit(() -> channel.config().setAutoRead(false)).syncUninterruptibly();
    }

    public void resumeReading() {
        awaitConnection().config().setAutoRead(true);
    }

    /** Stops answering {@code PING} and starts queueing {@code PING} and {@code PONG} for {@link #expect}. */
    public void silence() {
        silenced = true;
    }

    /**
     * Sends a normal WebSocket close if connected, stops the peer and releases every frame no test took.
     *
     * @throws AssertionError if the implementation overran a window and no earlier call reported it
     */
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
        if (!violationReported) {
            failOnViolation();
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
        failOnViolation();
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
        if (event instanceof Violation) {
            failOnViolation();
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

    private void failOnViolation() {
        AssertionError failure = violation;
        if (failure != null) {
            violationReported = true;
            throw failure;
        }
    }

    /** Records the first rule the implementation broke; the next call on the peer throws it. */
    private void violate(String message) {
        if (violation == null) {
            violation = new AssertionError(message);
            received.add(new Violation());
        }
    }

    private void onData(ChannelHandlerContext ctx, Frame.Data data) {
        String overrun = ledger.receive(data.streamId(), data.payload().readableBytes());
        if (overrun != null) {
            data.release();
            violate(overrun);
            return;
        }
        int bytes = data.payload().readableBytes();
        checkExchange(data.streamId(), rules -> {
            if (role == FrameCodec.Role.CLIENT) {
                rules.onRequestData(bytes);
            } else {
                rules.onResponseData(bytes);
            }
        });
        received.add(data);
        if (autoGrant) {
            ledger.due().forEach(ctx::writeAndFlush);
        }
    }

    private void onClose(Frame.Close close) {
        if (close.reset().isPresent()) {
            exchanges.remove(close.streamId());
            return;
        }
        checkExchange(close.streamId(), rules -> {
            if (role == FrameCodec.Role.CLIENT) {
                rules.onRequestEnd();
            } else {
                rules.onResponseEnd();
            }
        });
    }

    /**
     * Applies a frame from the implementation to its stream's HTTP rules, if the stream is an HTTP stream. The rules
     * throw {@link IllegalStateException} for the request side, which this peer receives as a client, and
     * {@link ProtocolException} for the response side, which it receives as a server; either way the implementation
     * broke the rule.
     */
    private void checkExchange(int streamId, Consumer<HttpExchangeRules> frame) {
        HttpExchangeRules rules = exchanges.get(streamId);
        if (rules == null) {
            return;
        }
        try {
            frame.accept(rules);
        } catch (IllegalStateException | ProtocolException broken) {
            violate("The implementation broke an HTTP stream rule on stream " + streamId + ": " + broken.getMessage());
        }
    }

    private static boolean offersHttp(List<String> capabilities) {
        return capabilities.contains(Capability.HTTP.wireName());
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
                case Frame.Data data -> onData(ctx, data);
                case Frame.Close close -> {
                    ledger.finish(close.streamId());
                    onClose(close);
                    received.add(close);
                }
                case Frame.Open open -> {
                    if (open.message().request() != null) {
                        exchanges.put(
                                open.streamId(),
                                new HttpExchangeRules(open.message().request()));
                    }
                    received.add(open);
                }
                case Frame.Headers headers -> {
                    if (tcpStreams.contains(headers.streamId())) {
                        violate("The implementation sent HEADERS on stream " + headers.streamId()
                                + ", a TCP tunnel's stream");
                    }
                    checkExchange(headers.streamId(), rules -> rules.onResponseHead(headers.head()));
                    received.add(headers);
                }
                case Frame.Hello hello -> {
                    sendCredit.announce(hello.message().windows());
                    received.add(hello);
                }
                case Frame.ServerHello hello -> {
                    sendCredit.announce(hello.message().windows());
                    http = httpOffered && offersHttp(hello.message().capabilities());
                    received.add(hello);
                }
                case Frame.WindowUpdate update -> {
                    sendCredit.grant(update.streamId(), update.increment());
                    if (!ignoreWindowUpdates) {
                        received.add(update);
                    }
                }
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
    /**
     * The implementation's credit towards this peer, kept as a correct receiver keeps it: each {@code DATA} takes from
     * its stream's credit and the session's, and each {@code WINDOW_UPDATE} this peer sends adds to one. Stream 0's
     * entries are the session's. Deliberately not the protocol's own flow model, which it exists to check.
     */
    private static final class Ledger {

        private Windows windows;
        private boolean started;
        private final Map<Integer, Long> credit = new HashMap<>();
        private final Map<Integer, Long> received = new HashMap<>();
        private final Map<Integer, Long> owed = new HashMap<>();
        private final Set<Integer> finished = new HashSet<>();

        Ledger(Windows windows) {
            this.windows = windows;
        }

        /** This peer's {@code HELLO}: its windows replace the reference ones, unless credit has already moved. */
        synchronized void announce(Windows announced) {
            if (!started) {
                windows = announced;
            }
        }

        /** Counts {@code DATA} from the implementation. Returns what it violated, or {@code null} if it fit. */
        synchronized String receive(int streamId, int bytes) {
            started = true;
            long stream = credit(streamId);
            long session = credit(StreamIds.CONTROL);
            if (bytes > session || bytes > stream) {
                return "Flow-control violation: the implementation sent " + bytes + " bytes of DATA on stream "
                        + streamId + " with " + stream + " bytes of stream credit and " + session
                        + " bytes of session credit left (this peer announced windows stream " + windows.stream()
                        + ", session " + windows.session() + ")";
            }
            for (int id : new int[] {streamId, StreamIds.CONTROL}) {
                credit.put(id, credit(id) - bytes);
                received.merge(id, (long) bytes, Long::sum);
                if (id == StreamIds.CONTROL || !finished.contains(id)) {
                    owed.merge(id, (long) bytes, Long::sum);
                }
            }
            return null;
        }

        synchronized void grant(int streamId, int increment) {
            started = true;
            credit.put(streamId, credit(streamId) + increment);
        }

        /** The implementation closed or reset the stream, or this peer reset it: no stream credit goes back. */
        synchronized void finish(int streamId) {
            finished.add(streamId);
            owed.remove(streamId);
        }

        /** The updates a correct receiver sends now: owed credit that has reached half its window, rounded down. */
        synchronized List<Frame.WindowUpdate> due() {
            List<Frame.WindowUpdate> updates = new ArrayList<>();
            for (Map.Entry<Integer, Long> entry : new ArrayList<>(owed.entrySet())) {
                int id = entry.getKey();
                long amount = entry.getValue();
                if (amount >= (id == StreamIds.CONTROL ? windows.session() : windows.stream()) / 2) {
                    owed.remove(id);
                    credit.put(id, credit(id) + amount);
                    updates.add(new Frame.WindowUpdate(id, (int) amount));
                }
            }
            return updates;
        }

        synchronized long credit(int streamId) {
            return credit.getOrDefault(
                    streamId, (long) (streamId == StreamIds.CONTROL ? windows.session() : windows.stream()));
        }

        synchronized long received(int streamId) {
            return received.getOrDefault(streamId, 0L);
        }
    }

    /**
     * This peer's credit towards the implementation, kept as a correct sender keeps it: the windows from the
     * implementation's {@code HELLO}, less every {@code DATA} sent, plus every {@code WINDOW_UPDATE} received. Stream
     * 0's entry is the session's.
     */
    private static final class SendCredit {

        private Windows windows;
        private boolean started;
        private final Map<Integer, Long> credit = new HashMap<>();

        SendCredit(Windows windows) {
            this.windows = windows;
        }

        synchronized void announce(Windows announced) {
            if (!started) {
                windows = announced;
            }
        }

        synchronized void spend(int streamId, int bytes) {
            started = true;
            credit.put(streamId, credit(streamId) - bytes);
            credit.put(StreamIds.CONTROL, credit(StreamIds.CONTROL) - bytes);
        }

        synchronized void grant(int streamId, int increment) {
            started = true;
            credit.put(streamId, credit(streamId) + increment);
            notifyAll();
        }

        synchronized void await(int streamId, int bytes, Duration timeout) {
            long deadline = System.nanoTime() + timeout.toNanos();
            while (credit(streamId) < bytes || credit(StreamIds.CONTROL) < bytes) {
                long left = deadline - System.nanoTime();
                if (left <= 0) {
                    throw new AssertionError("No credit for " + bytes + " bytes of DATA on stream " + streamId
                            + " within " + timeout + ": stream credit " + credit(streamId) + ", session credit "
                            + credit(StreamIds.CONTROL));
                }
                try {
                    TimeUnit.NANOSECONDS.timedWait(this, left);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError("Interrupted waiting for credit", e);
                }
            }
        }

        synchronized long credit(int streamId) {
            return credit.getOrDefault(
                    streamId, (long) (streamId == StreamIds.CONTROL ? windows.session() : windows.stream()));
        }
    }
}
