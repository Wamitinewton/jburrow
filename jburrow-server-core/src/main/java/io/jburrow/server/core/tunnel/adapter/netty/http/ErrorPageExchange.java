package io.jburrow.server.core.tunnel.adapter.netty.http;

import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.util.ReferenceCountUtil;
import java.util.Objects;

/**
 * One exchange answered with an error page, without forwarding anything: the page is written at once, and the
 * request's body is read to its end and discarded, so the connection can carry the next request.
 *
 * <p>The connection is closed after the page instead when the next request can't be found cheaply: the request
 * didn't decode, the visitor asked to close, it waits for {@code 100 Continue} before sending a body the edge won't
 * read, or its body is larger than {@code jburrow.http.max-request-body}. Discarding reads with {@code autoRead} on,
 * so the bound on a body of unknown length is checked as it arrives.
 *
 * <p>An exchange that fails after part of its body was forwarded gets its page the same way, with what is left of the
 * body discarded, or nothing to discard if the body had already ended.
 *
 * <p>Used on the connection's event loop only.
 */
public final class ErrorPageExchange {

    private final ChannelHandlerContext ctx;
    private final long maxRequestBody;
    private final boolean closeAfterPage;

    private long discarded;
    private boolean pageFlushed;
    private boolean bodyEnded;
    private boolean closing;

    private ErrorPageExchange(ChannelHandlerContext ctx, long maxRequestBody, boolean closeAfterPage) {
        this.ctx = ctx;
        this.maxRequestBody = maxRequestBody;
        this.closeAfterPage = closeAfterPage;
    }

    /**
     * Writes the page for {@code request} through {@code ctx} and returns the exchange, which the caller then gives
     * every piece of the request's body, in order.
     */
    public static ErrorPageExchange answer(
            ChannelHandlerContext ctx,
            HttpRequest request,
            String host,
            EdgeError error,
            EdgeErrorPage pages,
            long maxRequestBody) {
        return answer(ctx, request, host, error, pages, maxRequestBody, false, false);
    }

    /**
     * {@link #answer(ChannelHandlerContext, HttpRequest, String, EdgeError, EdgeErrorPage, long)} for an exchange that
     * has already read part of its request.
     *
     * @param bodyEnded whether the request's body has been read to its end, so nothing is left to discard
     * @param close whether to close the connection after the page whatever the request says, for a body the edge
     *     can't read on from
     */
    static ErrorPageExchange answer(
            ChannelHandlerContext ctx,
            HttpRequest request,
            String host,
            EdgeError error,
            EdgeErrorPage pages,
            long maxRequestBody,
            boolean bodyEnded,
            boolean close) {
        Objects.requireNonNull(request, "request");
        // A head that didn't decode may have a content-length that doesn't parse.
        boolean decoded = request.decoderResult().isSuccess();
        boolean hasBody = decoded && !bodyEnded && hasBody(request);
        boolean closeAfterPage = close
                || !decoded
                || !HttpUtil.isKeepAlive(request)
                || (hasBody && HttpUtil.is100ContinueExpected(request))
                || HttpUtil.getContentLength(request, 0L) > maxRequestBody;
        ErrorPageExchange exchange = new ErrorPageExchange(ctx, maxRequestBody, closeAfterPage);
        exchange.bodyEnded = bodyEnded;
        if (hasBody && !closeAfterPage) {
            ctx.channel().config().setAutoRead(true);
        }
        ctx.writeAndFlush(pages.render(error, host, HttpMethod.HEAD.equals(request.method()), closeAfterPage))
                .addListener((ChannelFuture write) -> exchange.pageWritten(write));
        return exchange;
    }

    /** Discards one piece of the request's body, and releases it. */
    public void onContent(Object msg) {
        try {
            if (closing || closeAfterPage || !(msg instanceof HttpContent content)) {
                return;
            }
            discarded += content.content().readableBytes();
            if (content.decoderResult().isFailure() || discarded > maxRequestBody) {
                close();
            } else if (content instanceof LastHttpContent) {
                bodyEnded = true;
                endIfDone();
            }
        } finally {
            ReferenceCountUtil.release(msg);
        }
    }

    private void pageWritten(ChannelFuture write) {
        pageFlushed = true;
        if (!write.isSuccess() || closeAfterPage) {
            close();
        } else {
            endIfDone();
        }
    }

    /** Closes once the page is out, so a visitor whose body is cut short still gets it. */
    private void close() {
        closing = true;
        if (pageFlushed) {
            ctx.close();
        }
    }

    private void endIfDone() {
        if (pageFlushed && bodyEnded && !closing) {
            ctx.pipeline().fireUserEventTriggered(ExchangeEnded.INSTANCE);
        }
    }

    private static boolean hasBody(HttpRequest request) {
        return HttpUtil.isTransferEncodingChunked(request) || HttpUtil.getContentLength(request, 0L) > 0;
    }
}
