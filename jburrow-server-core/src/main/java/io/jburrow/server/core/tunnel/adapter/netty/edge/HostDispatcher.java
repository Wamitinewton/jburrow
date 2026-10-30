package io.jburrow.server.core.tunnel.adapter.netty.edge;

import io.jburrow.protocol.control.SubdomainName;
import io.jburrow.server.core.tunnel.adapter.netty.http.EdgeError;
import io.jburrow.server.core.tunnel.adapter.netty.http.EdgeErrorPage;
import io.jburrow.server.core.tunnel.adapter.netty.http.ErrorPageExchange;
import io.jburrow.server.core.tunnel.adapter.netty.http.ExchangeEnded;
import io.jburrow.server.core.tunnel.adapter.netty.http.ExchangeRequest;
import io.jburrow.server.core.tunnel.application.port.TunnelMetrics;
import io.jburrow.server.core.tunnel.application.port.TunnelMetrics.ResponseOrigin;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelOption;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.util.AttributeKey;
import io.netty.util.ReferenceCountUtil;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Decides what each request on an edge connection is for, from its {@code Host}:
 * {@code docs/phase-3-http-tunnels/ARCHITECTURE.md} §Dispatch on {@code Host}. The connect host gets phase 1's
 * pipeline, installed behind this handler; a host one valid label under {@code jburrow.http.domain} goes to the
 * {@code ExchangeHandler}; anything else is answered with the {@code TUNNEL_NOT_FOUND} page.
 *
 * <p>One exchange at a time: a head turns {@code autoRead} off, and the exchange reads its own body. Anything decoded
 * after the request's end, which is at most what one read held, waits here until the exchange ends, so a pipelined
 * request is never served early. While an exchange waits for its response with nothing held, one read stays
 * outstanding, so a visitor that closes is noticed at once whatever the transport, rather than when the response is
 * written. Connect mode is the connection's last: it either upgrades, which removes this handler, or is refused and
 * closed.
 */
final class HostDispatcher extends ChannelInboundHandlerAdapter {

    static final String EXCHANGE = "exchange";

    /** Set on a connection once it has carried a tunnel request: its exchange handler is added, its socket sized. */
    static final AttributeKey<Boolean> CARRIES_TUNNELS = AttributeKey.valueOf(HostDispatcher.class, "carriesTunnels");

    private static final Logger log = LoggerFactory.getLogger(HostDispatcher.class);

    private enum State {
        BETWEEN_EXCHANGES,
        EXCHANGE,
        CONNECT
    }

    private final String connectHost;
    private final HttpSettings http;
    private final long requestTimeoutNanos;
    private final ConnectPipeline connect;
    private final ClientAddressResolver clientAddresses;
    private final Supplier<? extends ChannelHandler> exchanges;
    private final EdgeErrorPage pages;
    private final RequestDeadline deadline;
    private final TunnelMetrics metrics;

    private final ArrayDeque<Object> held = new ArrayDeque<>();
    private State state = State.BETWEEN_EXCHANGES;
    /** Whether the current exchange's request has been decoded to its end. */
    private boolean requestEnded;
    /** The current exchange, when this handler answers it with a page itself. */
    private ErrorPageExchange page;

    private boolean servedExchange;
    private boolean draining;
    /** Whether the current exchange's read for a close has been issued. */
    private boolean watching;

    HostDispatcher(
            String connectHost,
            HttpSettings http,
            Duration requestTimeout,
            ConnectPipeline connect,
            ClientAddressResolver clientAddresses,
            Supplier<? extends ChannelHandler> exchanges,
            EdgeErrorPage pages,
            RequestDeadline deadline,
            TunnelMetrics metrics) {
        this.connectHost = normalize(Objects.requireNonNull(connectHost, "connectHost"));
        this.http = Objects.requireNonNull(http, "http");
        this.requestTimeoutNanos = requestTimeout.toNanos();
        this.connect = Objects.requireNonNull(connect, "connect");
        this.clientAddresses = Objects.requireNonNull(clientAddresses, "clientAddresses");
        this.exchanges = Objects.requireNonNull(exchanges, "exchanges");
        this.pages = Objects.requireNonNull(pages, "pages");
        this.deadline = Objects.requireNonNull(deadline, "deadline");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
    }

    /**
     * A {@code Host} value as the dispatcher compares it: without surrounding space, a port or an IPv6 literal's
     * brackets, and ASCII lower-case. Only ASCII letters are folded, as in {@link ConnectRequestFilter}.
     */
    static String normalize(String host) {
        String value = ConnectRequestFilter.withoutPort(host);
        if (value.length() >= 2 && value.startsWith("[") && value.endsWith("]")) {
            value = value.substring(1, value.length() - 1);
        }
        StringBuilder lower = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            lower.append(c >= 'A' && c <= 'Z' ? (char) (c + ('a' - 'A')) : c);
        }
        return lower.toString();
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        dispatch(ctx, msg);
    }

    private void dispatch(ChannelHandlerContext ctx, Object msg) {
        switch (state) {
            case CONNECT -> ctx.fireChannelRead(msg);
            case EXCHANGE -> {
                if (requestEnded) {
                    held.add(msg);
                } else {
                    content(ctx, msg);
                }
            }
            case BETWEEN_EXCHANGES -> {
                if (msg instanceof HttpRequest request) {
                    head(ctx, request);
                } else {
                    // The codec emits nothing between a request's end and the next head.
                    ReferenceCountUtil.release(msg);
                }
            }
        }
    }

    private void head(ChannelHandlerContext ctx, HttpRequest request) {
        if (request.decoderResult().isFailure()) {
            answer(ctx, request, EdgeError.BAD_REQUEST, "");
            requestEnded = true;
            ReferenceCountUtil.release(request);
            return;
        }
        List<String> hosts = request.headers().getAll(HttpHeaderNames.HOST);
        if (hosts.size() != 1) {
            answer(ctx, request, EdgeError.BAD_REQUEST, "");
            return;
        }
        String host = normalize(hosts.get(0));
        if (host.equals(connectHost)) {
            connect(ctx, request);
            return;
        }
        Optional<SubdomainName> name = SubdomainName.underDomain(host, http.domain());
        if (name.isPresent()) {
            tunnel(ctx, request, host, name.get());
        } else {
            answer(ctx, request, EdgeError.TUNNEL_NOT_FOUND, host);
        }
    }

    /**
     * Hands the connection to phase 1's handlers, with {@code autoRead} left on as phase 1 had it: the aggregator
     * reads the upgrade request whole, and the session reads from the upgrade on. A connection that served exchanges
     * first gets a fresh {@code request-timeout} for its upgrade.
     */
    private void connect(ChannelHandlerContext ctx, HttpRequest request) {
        state = State.CONNECT;
        if (carriesTunnels(ctx.channel())) {
            ctx.pipeline().remove(EXCHANGE);
        }
        if (servedExchange) {
            deadline.arm(requestTimeoutNanos);
        }
        connect.install(ctx.pipeline(), ctx.name());
        ctx.fireChannelRead(request);
    }

    private void tunnel(ChannelHandlerContext ctx, HttpRequest request, String host, SubdomainName name) {
        beginExchange(ctx);
        if (ctx.channel().attr(CARRIES_TUNNELS).setIfAbsent(true) == null) {
            sizeSocketBuffers(ctx.channel());
            ctx.pipeline().addAfter(ctx.name(), EXCHANGE, exchanges.get());
        }
        Channel channel = ctx.channel();
        ctx.fireChannelRead(new ExchangeRequest(
                request, host, name, clientAddresses.visitor(channel, request), clientAddresses.trustsPeer(channel)));
    }

    static boolean carriesTunnels(Channel channel) {
        return Boolean.TRUE.equals(channel.attr(CARRIES_TUNNELS).get());
    }

    private void answer(ChannelHandlerContext ctx, HttpRequest request, EdgeError error, String host) {
        beginExchange(ctx);
        metrics.edgeErrorPage(error.name());
        metrics.httpResponse(error.status(), ResponseOrigin.EDGE);
        log.debug(
                "edge page: client={} code={}",
                ClientAddressResolver.clientAddress(ctx.channel()).getHostAddress(),
                error);
        page = ErrorPageExchange.answer(ctx, request, host, error, pages, http.maxRequestBody());
    }

    private void beginExchange(ChannelHandlerContext ctx) {
        state = State.EXCHANGE;
        requestEnded = false;
        watching = false;
        page = null;
        servedExchange = true;
        ctx.channel().config().setAutoRead(false);
        deadline.disarm();
    }

    private void content(ChannelHandlerContext ctx, Object msg) {
        if (msg instanceof LastHttpContent) {
            requestEnded = true;
            ctx.channel().config().setAutoRead(false);
        }
        if (page != null) {
            page.onContent(msg);
        } else {
            ctx.fireChannelRead(msg);
        }
    }

    /**
     * The socket settings of {@code docs/phase-3-http-tunnels/ARCHITECTURE.md} §Dispatch on {@code Host}: they bound
     * the kernel memory an exchange can hold, and a control session keeps the kernel's auto-tuning.
     */
    private void sizeSocketBuffers(Channel channel) {
        if (http.socketBuffer() != HttpSettings.KERNEL_SIZED) {
            channel.config().setOption(ChannelOption.SO_SNDBUF, http.socketBuffer());
            channel.config().setOption(ChannelOption.SO_RCVBUF, http.socketBuffer());
        }
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object event) throws Exception {
        if (event == ExchangeEnded.INSTANCE) {
            if (state == State.EXCHANGE) {
                endExchange(ctx);
            }
            return;
        }
        super.userEventTriggered(ctx, event);
    }

    private void endExchange(ChannelHandlerContext ctx) {
        if (!requestEnded) {
            // Without the request's end there is no telling where the next one starts.
            ctx.close();
            return;
        }
        state = State.BETWEEN_EXCHANGES;
        page = null;
        deadline.arm(http.keepAliveTimeout().toNanos());
        drainHeld(ctx);
        if (state == State.BETWEEN_EXCHANGES) {
            ctx.channel().config().setAutoRead(true);
        }
    }

    /** Dispatches what was held back, as if it had just been read, until an exchange needs to hold it again. */
    private void drainHeld(ChannelHandlerContext ctx) {
        if (draining || held.isEmpty()) {
            return;
        }
        draining = true;
        try {
            while (!held.isEmpty() && !(state == State.EXCHANGE && requestEnded)) {
                dispatch(ctx, held.poll());
            }
        } finally {
            draining = false;
        }
        watchForClose(ctx);
        ctx.fireChannelReadComplete();
    }

    @Override
    public void channelReadComplete(ChannelHandlerContext ctx) {
        watchForClose(ctx);
        ctx.fireChannelReadComplete();
    }

    /**
     * Once the request has been read to its end with nothing behind it, reads once more: NIO only notices a peer's
     * {@code FIN} while a read is outstanding, and an exchange waiting for a slow response would otherwise hold its
     * stream until the response is written. What the read brings is held like any other pipelined bytes.
     */
    private void watchForClose(ChannelHandlerContext ctx) {
        if (state == State.EXCHANGE && requestEnded && held.isEmpty() && !watching) {
            watching = true;
            ctx.read();
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        releaseHeld();
        ctx.fireChannelInactive();
    }

    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) {
        releaseHeld();
    }

    private void releaseHeld() {
        Object msg;
        while ((msg = held.poll()) != null) {
            ReferenceCountUtil.release(msg);
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        if (state == State.CONNECT) {
            ctx.fireExceptionCaught(cause);
            return;
        }
        log.debug(
                "Edge connection failed: client {}",
                ClientAddressResolver.clientAddress(ctx.channel()).getHostAddress(),
                cause);
        ctx.close();
    }
}
