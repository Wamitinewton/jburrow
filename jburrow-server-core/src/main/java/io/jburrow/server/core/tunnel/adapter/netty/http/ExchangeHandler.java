package io.jburrow.server.core.tunnel.adapter.netty.http;

import io.jburrow.server.core.tunnel.application.port.TunnelMetrics.ResponseOrigin;
import io.jburrow.server.core.tunnel.domain.TunnelId;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.CompositeByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.util.ReferenceCountUtil;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The edge's half of the HTTP exchanges of one edge connection, on the connection's event loop
 * ({@code docs/phase-3-http-tunnels/ARCHITECTURE.md} §An exchange). The dispatcher adds it behind itself the first
 * time the connection carries a request for a tunnel host, and passes it an {@link ExchangeRequest} then the request's
 * body for each one; the exchange ends with {@link ExchangeEnded} or by closing the connection.
 *
 * <p>For each request it makes the checks the edge can make alone, routes the name to its session, builds the head,
 * and hands the exchange to the session's {@link SessionExchange}, with the bytes of body the head's own read took. From
 * then on it touches nothing of the session's: it reads the request body only when the session asks, one read of
 * exactly the reserved size at a time, and writes the response as the session sends it.
 *
 * <p>Every message from the session names its exchange. One for an exchange that is no longer this connection's
 * current one is dropped, releasing any buffer it carries, except a read request, which is answered at once so its
 * reservation goes back, and a write, whose bytes are reported back as released.
 *
 * <p>Confined to the connection's event loop.
 */
public final class ExchangeHandler extends ChannelInboundHandlerAdapter {

    private static final Logger log = LoggerFactory.getLogger(ExchangeHandler.class);

    private enum Phase {
        /** Between exchanges. */
        IDLE,
        /** The head has been routed; the rest of its read may hold body bytes. */
        HEAD,
        /** The session has the exchange. */
        FORWARDING,
        /** The response's end is being written. */
        ENDING,
        /** The edge answers with a page and discards the rest of the body. */
        PAGE
    }

    private final ExchangeServices services;

    private ChannelHandlerContext ctx;
    private DemandReadAllocator reads;
    private Phase phase = Phase.IDLE;
    private ExchangeRequest request;
    private SessionExchange session;
    private ByteBuf leftover;
    private boolean requestEnded;
    private boolean demanding;
    private boolean headWritten;
    private boolean closeAfterResponse;
    private ErrorPageExchange page;

    public ExchangeHandler(ExchangeServices services) {
        this.services = Objects.requireNonNull(services, "services");
    }

    @Override
    public void handlerAdded(ChannelHandlerContext ctx) {
        this.ctx = ctx;
        this.reads = DemandReadAllocator.of(ctx.channel());
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (msg instanceof ExchangeRequest head) {
            onHead(head);
            return;
        }
        switch (phase) {
            case HEAD -> onLeftover(msg);
            case FORWARDING, ENDING -> onBody(msg);
            case PAGE -> page.onContent(msg);
            case IDLE -> ReferenceCountUtil.release(msg);
        }
    }

    @Override
    public void channelReadComplete(ChannelHandlerContext ctx) {
        if (phase == Phase.HEAD) {
            handOver();
        } else if (demanding) {
            demanding = false;
            session.readDone();
        }
        ctx.fireChannelReadComplete();
    }

    // A new request.

    private void onHead(ExchangeRequest head) {
        request = head;
        session = null;
        page = null;
        requestEnded = false;
        demanding = false;
        headWritten = false;
        closeAfterResponse = false;
        reads.clear();
        Optional<EdgeError> refused = services.shuttingDown()
                ? Optional.of(EdgeError.SERVER_SHUTTING_DOWN)
                : services.heads().check(head.head());
        if (refused.isPresent()) {
            answer(refused.get(), false);
            return;
        }
        Optional<TunnelId> tunnel = services.routes().find(head.name());
        Optional<SessionExchanges> exchanges = tunnel.flatMap(services.sessions()::find);
        if (exchanges.isEmpty()) {
            answer(EdgeError.TUNNEL_NOT_FOUND, false);
            return;
        }
        switch (services.heads().open(head.head(), tunnel.get().value(), head.visitor(), head.viaTrustedProxy())) {
            case RequestHeads.Result.Refuse refuse -> answer(refuse.error(), false);
            case RequestHeads.Result.Forward forward -> {
                HttpRequest decoded = head.head();
                boolean http11 = decoded.protocolVersion().equals(HttpVersion.HTTP_1_1);
                session = new SessionExchange(
                        exchanges.get(),
                        this,
                        ctx.executor(),
                        tunnel.get(),
                        forward.open(),
                        head.host(),
                        HttpUtil.is100ContinueExpected(decoded),
                        http11 && HttpUtil.isKeepAlive(decoded),
                        http11);
                phase = Phase.HEAD;
            }
        }
    }

    /** What the head's own read took of the body, copied onto the heap until the exchange is handed over. */
    private void onLeftover(Object msg) {
        try {
            if (!(msg instanceof HttpContent content)) {
                return;
            }
            if (content.decoderResult().isFailure()) {
                session = null;
                answer(EdgeError.BAD_REQUEST, true);
                return;
            }
            if (content.content().isReadable()) {
                ByteBuf onHeap = Unpooled.copiedBuffer(content.content());
                if (leftover == null) {
                    leftover = onHeap;
                } else {
                    if (!(leftover instanceof CompositeByteBuf)) {
                        leftover = Unpooled.compositeBuffer().addComponent(true, leftover);
                    }
                    ((CompositeByteBuf) leftover).addComponent(true, onHeap);
                }
            }
            if (content instanceof LastHttpContent) {
                requestEnded = true;
            }
        } finally {
            ReferenceCountUtil.release(msg);
        }
    }

    /** The head's read is over: the session takes the exchange from here. */
    private void handOver() {
        ByteBuf taken = leftover;
        leftover = null;
        phase = Phase.FORWARDING;
        if (!session.submit(taken, requestEnded)) {
            session = null;
            answer(EdgeError.SERVER_SHUTTING_DOWN, false);
        }
    }

    /** Body content a demand read produced, passed to the session as it is. */
    private void onBody(Object msg) {
        if (!(msg instanceof HttpContent content) || requestEnded) {
            ReferenceCountUtil.release(msg);
            return;
        }
        if (content.decoderResult().isFailure()) {
            content.release();
            requestEnded = true;
            session.badBody();
            return;
        }
        if (content.content().isReadable()) {
            session.content(content.content());
        } else {
            content.release();
        }
        if (content instanceof LastHttpContent) {
            requestEnded = true;
            session.requestEnd();
        }
    }

    /**
     * Answers the current request with {@code error}'s page, after which the rest of its body is discarded.
     *
     * @param close whether the connection can't carry another request after this one, whatever the request says
     */
    private void answer(EdgeError error, boolean close) {
        phase = Phase.PAGE;
        reads.clear();
        demanding = false;
        releaseLeftover();
        services.metrics().edgeErrorPage(error.name());
        services.metrics().httpResponse(error.status(), ResponseOrigin.EDGE);
        log.debug(
                "edge page: host={} method={} code={}",
                request.host(),
                request.head().method().name(),
                error);
        page = ErrorPageExchange.answer(
                ctx,
                request.head(),
                request.host(),
                error,
                services.pages(),
                services.maxRequestBody(),
                requestEnded,
                close);
    }

    // Messages from the session, each run on this loop as a task the session posted.

    /** Reads the visitor once, at most {@code bytes} of it. Every request is answered with one {@code readDone}. */
    void readOnce(SessionExchange from, int bytes) {
        requireLoop();
        if (from != session
                || phase != Phase.FORWARDING
                || requestEnded
                || !ctx.channel().isActive()) {
            from.readDone();
            return;
        }
        demanding = true;
        reads.demand(bytes);
        ctx.channel().read();
    }

    void writeContinue(SessionExchange from) {
        requireLoop();
        if (current(from) && !headWritten) {
            ctx.writeAndFlush(new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.CONTINUE));
        }
    }

    void writeInformational(SessionExchange from, HttpResponse informational) {
        requireLoop();
        if (current(from) && !headWritten) {
            ctx.write(informational);
        }
    }

    void writeHead(SessionExchange from, HttpResponse response, boolean closeAfter) {
        requireLoop();
        if (current(from)) {
            headWritten = true;
            closeAfterResponse = closeAfter;
            ctx.write(response);
        }
    }

    /** Writes response body; its release goes back to the session once the write is done, either way. */
    void writeContent(SessionExchange from, ByteBuf content) {
        requireLoop();
        int bytes = content.readableBytes();
        if (!current(from) || !headWritten) {
            content.release();
            from.written(bytes);
            return;
        }
        ChannelPromise written = ctx.newPromise();
        written.addListener(done -> from.written(bytes));
        ctx.write(new DefaultHttpContent(content), written);
    }

    void flush(SessionExchange from) {
        requireLoop();
        if (current(from)) {
            ctx.flush();
        }
    }

    /**
     * Writes the end of the response. Once it is out, the connection carries the next request if the request was read
     * to its end and nothing asked to close; otherwise it closes.
     */
    void writeEnd(SessionExchange from) {
        requireLoop();
        if (!current(from)) {
            return;
        }
        phase = Phase.ENDING;
        ctx.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT).addListener((ChannelFuture write) -> {
            if (session != from) {
                return;
            }
            if (write.isSuccess() && requestEnded && !closeAfterResponse) {
                endExchange();
                ctx.pipeline().fireUserEventTriggered(ExchangeEnded.INSTANCE);
            } else {
                ctx.close();
            }
        });
    }

    /**
     * The response started and can't be finished: the visitor sees it cut short, after whatever of it arrived before the
     * failure, which may still wait for the session's flush.
     */
    void abort(SessionExchange from) {
        requireLoop();
        if (current(from)) {
            ctx.flush();
            ctx.close();
        }
    }

    /** The exchange failed before its response started: the visitor gets {@code error}'s page. */
    void page(SessionExchange from, EdgeError error) {
        requireLoop();
        if (!current(from)) {
            return;
        }
        if (headWritten) {
            ctx.close();
            return;
        }
        session = null;
        answer(error, error == EdgeError.BAD_REQUEST || error == EdgeError.REQUEST_TOO_LARGE);
    }

    private boolean current(SessionExchange from) {
        return from == session && (phase == Phase.FORWARDING || phase == Phase.ENDING);
    }

    private void endExchange() {
        phase = Phase.IDLE;
        session = null;
        request = null;
        reads.clear();
    }

    // The connection going away.

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        visitorGone();
        ctx.fireChannelInactive();
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.debug("Edge connection failed during an exchange", cause);
        visitorGone();
        ctx.close();
    }

    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) {
        releaseLeftover();
    }

    /** A connection that closes between exchanges tells no session. */
    private void visitorGone() {
        releaseLeftover();
        if (session != null && phase != Phase.HEAD) {
            SessionExchange gone = session;
            session = null;
            demanding = false;
            gone.visitorGone();
        }
        session = null;
    }

    private void releaseLeftover() {
        if (leftover != null) {
            leftover.release();
            leftover = null;
        }
    }

    private void requireLoop() {
        if (!ctx.executor().inEventLoop()) {
            throw new IllegalStateException("An edge connection's exchange touched off its event loop");
        }
    }
}
