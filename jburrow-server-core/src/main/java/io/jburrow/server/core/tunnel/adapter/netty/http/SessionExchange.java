package io.jburrow.server.core.tunnel.adapter.netty.http;

import io.jburrow.protocol.control.BodyFraming;
import io.jburrow.protocol.control.HttpResponseHead;
import io.jburrow.protocol.control.Open;
import io.jburrow.protocol.wire.ErrorCode;
import io.jburrow.server.core.tunnel.application.port.TunnelMetrics.ResponseOrigin;
import io.jburrow.server.core.tunnel.domain.TunnelId;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.CompositeByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.util.concurrent.EventExecutor;
import io.netty.util.concurrent.ScheduledFuture;
import java.util.Objects;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * The session's half of one HTTP exchange, {@code docs/phase-3-http-tunnels/ARCHITECTURE.md} §An exchange: its stream,
 * the request body it sends as credit allows, and the response it hands to the edge. Its {@link ExchangeHandler} is the
 * other half, on the edge connection's loop.
 *
 * <p>The two halves share no state. Each method the edge calls posts itself to the session's loop, and each message to
 * the edge is a task on the edge's loop. Messages to the edge go out in the order they are made, response content
 * included, so a response's head, body and end can't reorder even when both halves happen to share a loop. A message
 * for an exchange that has ended is dropped on arrival, with any buffer it carries released, except a completed write,
 * whose bytes are released whenever it arrives.
 *
 * <p>The request body: a read of the visitor is asked for only once the {@link ExchangeGate} has reserved credit for
 * it, and what the read produces goes out against that reservation. Bytes that arrive without one, the head's own read
 * of the body or what epoll drains from a visitor that sent {@code FIN}, are held on the heap and sent first, out of
 * later reservations.
 *
 * <p>Confined to the session's event loop, apart from the methods the edge calls, which say so.
 */
final class SessionExchange implements ExchangeGate.Reader {

    private final SessionExchanges owner;
    private final ExchangeHandler edge;
    private final EventExecutor edgeLoop;
    private final TunnelId tunnel;
    private final Open open;
    private final String host;
    private final boolean expectContinue;
    private final boolean keepAliveRequested;
    private final boolean chunkedAllowed;
    private final boolean headRequest;

    private int streamId;
    private long openedNanos;
    /** Request body held on the heap until a reservation covers it. */
    private ByteBuf held;
    /** Credit reserved for the read in progress and not yet spent. */
    private int grant;

    private boolean reading;
    private boolean bodyComplete;
    private boolean closeSent;
    private boolean finalHeadSent;
    private boolean responseEnded;
    private boolean flushPending;
    private boolean finished;
    private long bodyBytes;
    private long bytesIn;
    private long bytesOut;
    /** The final status the visitor got, a page's included, or 0 before one; for the exchange's log line. */
    private int status;
    /** From {@code OPEN} to the final head, or -1 before it. */
    private long headNanos = -1;

    private EdgeError page;
    private ErrorCode resetCode;
    private ScheduledFuture<?> responseTimer;

    /** Made on the edge's loop; touches nothing until its first task runs on the session's loop. */
    SessionExchange(
            SessionExchanges owner,
            ExchangeHandler edge,
            EventExecutor edgeLoop,
            TunnelId tunnel,
            Open open,
            String host,
            boolean expectContinue,
            boolean keepAliveRequested,
            boolean chunkedAllowed) {
        this.owner = Objects.requireNonNull(owner, "owner");
        this.edge = Objects.requireNonNull(edge, "edge");
        this.edgeLoop = Objects.requireNonNull(edgeLoop, "edgeLoop");
        this.tunnel = Objects.requireNonNull(tunnel, "tunnel");
        this.open = Objects.requireNonNull(open, "open");
        this.host = Objects.requireNonNull(host, "host");
        this.expectContinue = expectContinue;
        this.keepAliveRequested = keepAliveRequested;
        this.chunkedAllowed = chunkedAllowed;
        this.headRequest = open.request().method().equals("HEAD");
    }

    // Called on the edge's loop.

    /**
     * The head has been read, with {@code leftover} bytes of the body behind it, on the heap, and the request's end if
     * that read reached it. Takes over {@code leftover}, which may be null.
     *
     * @return false if the session's loop has stopped, so the exchange can't start
     */
    boolean submit(ByteBuf leftover, boolean requestEnded) {
        return post(() -> owner.open(this, leftover, requestEnded), leftover);
    }

    /** Body content a read produced. Takes over {@code content}. */
    void content(ByteBuf content) {
        post(() -> onContent(content), content);
    }

    /** The read {@link ExchangeHandler#readOnce} asked for is done, or won't happen. */
    void readDone() {
        post(this::onReadDone, null);
    }

    /** The request's body has been read to its end. */
    void requestEnd() {
        post(this::onRequestEnd, null);
    }

    /** The request's body didn't decode. */
    void badBody() {
        post(() -> fail(EdgeError.BAD_REQUEST), null);
    }

    /** The visitor's connection closed or failed with the exchange in progress. */
    void visitorGone() {
        post(this::onVisitorGone, null);
    }

    /** A write of {@code bytes} of response body to the visitor completed or failed. */
    void written(int bytes) {
        post(() -> owner.onWritten(streamId, bytes), null);
    }

    private boolean post(Runnable task, ByteBuf carried) {
        try {
            owner.loop().execute(task);
            return true;
        } catch (RejectedExecutionException stopped) {
            if (carried != null) {
                carried.release();
            }
            return false;
        }
    }

    // The session's loop: opening.

    /** The session admitted the exchange on {@code streamId} and sent its {@code OPEN}. */
    void opened(int streamId, long nowNanos, ByteBuf leftover, boolean requestEnded) {
        requireLoop();
        this.streamId = streamId;
        this.openedNanos = nowNanos;
        this.bodyComplete = requestEnded;
        if (leftover != null && leftover.isReadable()) {
            hold(leftover);
        } else if (leftover != null) {
            leftover.release();
        }
        responseTimer =
                owner.loop().schedule(this::onResponseTimeout, owner.responseTimeoutNanos(), TimeUnit.NANOSECONDS);
        if (open.request().body() == BodyFraming.EMPTY) {
            bodyComplete = true;
            releaseHeld();
        } else if (expectContinue && !bodyComplete) {
            toEdge(() -> edge.writeContinue(this));
        }
        progress();
    }

    /** The session refused the exchange before it had a stream. */
    void refused(EdgeError error, ByteBuf leftover) {
        requireLoop();
        if (leftover != null) {
            leftover.release();
        }
        finished = true;
        page = error;
        status = error.status();
        toEdge(() -> edge.page(this, error));
        ExchangeLog.ended(outcome(0));
    }

    Open open() {
        return open;
    }

    TunnelId tunnel() {
        return tunnel;
    }

    String host() {
        return host;
    }

    @Override
    public int streamId() {
        return streamId;
    }

    // The request body.

    @Override
    public void resume() {
        progress();
    }

    /**
     * Moves the request body on as far as credit allows: sends what is held, then asks the edge for a read covering
     * the rest of a grant, or sends {@code CLOSE} once the body is complete. One grant at a time, and an exchange that
     * used a grant takes its turn behind any others waiting.
     */
    private void progress() {
        requireLoop();
        while (!finished && !closeSent && !reading) {
            if (held == null && bodyComplete) {
                sendClose();
                return;
            }
            int granted = owner.gate().grant(this);
            if (granted == 0) {
                return;
            }
            if (held != null) {
                granted -= sendHeld(granted);
                owner.sessionChannel().flush();
            }
            if (held == null && bodyComplete) {
                owner.session().refund(streamId, granted);
                sendClose();
                return;
            }
            if (granted > 0) {
                reading = true;
                grant = granted;
                int demand = granted;
                toEdge(() -> edge.readOnce(this, demand));
                return;
            }
            if (!owner.gate().turnTaken(this)) {
                return;
            }
        }
    }

    private void onContent(ByteBuf content) {
        requireLoop();
        int length = content.readableBytes();
        if (finished || length == 0) {
            content.release();
            return;
        }
        bodyBytes += length;
        if (open.request().body() == BodyFraming.CHUNKED && bodyBytes > owner.maxRequestBody()) {
            content.release();
            fail(EdgeError.REQUEST_TOO_LARGE);
            return;
        }
        if (grant == 0 || held != null) {
            hold(content);
            if (!reading) {
                progress();
            }
            return;
        }
        int sending = Math.min(grant, length);
        ByteBuf data = sending == length ? content : content.readRetainedSlice(sending);
        grant -= sending;
        sendData(data);
        if (sending < length) {
            hold(content);
        }
    }

    private void onReadDone() {
        requireLoop();
        if (finished) {
            return;
        }
        reading = false;
        if (grant > 0) {
            owner.session().refund(streamId, grant);
            grant = 0;
        }
        owner.sessionChannel().flush();
        if (held == null && bodyComplete) {
            sendClose();
        } else if (owner.gate().turnTaken(this)) {
            progress();
        }
    }

    private void onRequestEnd() {
        requireLoop();
        if (finished) {
            return;
        }
        bodyComplete = true;
        progress();
    }

    /** Sends up to {@code granted} held bytes, as one {@code DATA}, and returns how many. */
    private int sendHeld(int granted) {
        int sending = Math.min(granted, held.readableBytes());
        ByteBuf data;
        if (sending == held.readableBytes()) {
            data = held;
            held = null;
        } else {
            data = held.readRetainedSlice(sending);
        }
        sendData(data);
        return sending;
    }

    /** Sends reserved {@code DATA}, unflushed. Takes over {@code data}. */
    private void sendData(ByteBuf data) {
        int length = data.readableBytes();
        owner.session().onReservedDataSent(streamId, length);
        bytesIn += length;
        owner.sendData(streamId, data);
    }

    /**
     * Holds {@code bytes} behind what is already held, copied onto the heap, which the memory budget allows for: at
     * most one read of under 4 KiB with a head, or what epoll drained from a socket buffer.
     */
    private void hold(ByteBuf bytes) {
        ByteBuf onHeap = Unpooled.copiedBuffer(bytes);
        bytes.release();
        if (held == null) {
            held = onHeap;
            return;
        }
        if (!(held instanceof CompositeByteBuf)) {
            held = Unpooled.compositeBuffer().addComponent(true, held);
        }
        ((CompositeByteBuf) held).addComponent(true, onHeap);
    }

    private void releaseHeld() {
        if (held != null) {
            held.release();
            held = null;
        }
    }

    private void sendClose() {
        closeSent = true;
        owner.sendClose(streamId);
        if (owner.session().streamState(streamId).isEmpty()) {
            finish("closed");
        }
    }

    // The response.

    void onResponseHead(HttpResponseHead head) {
        requireLoop();
        if (head.informational()) {
            HttpResponse informational = ResponseHeads.build(head, headRequest, chunkedAllowed, false);
            toEdge(() -> edge.writeInformational(this, informational));
            markWritten();
            return;
        }
        cancelResponseTimer();
        finalHeadSent = true;
        status = head.status();
        headNanos = owner.nanoTime() - openedNanos;
        owner.metrics().responseHead(headNanos);
        owner.metrics().httpResponse(head.status(), ResponseOrigin.TUNNEL);
        boolean close = !keepAliveRequested || ResponseHeads.closeDelimited(head, chunkedAllowed);
        HttpResponse response = ResponseHeads.build(head, headRequest, chunkedAllowed, close);
        toEdge(() -> edge.writeHead(this, response, close));
        markWritten();
    }

    /** Response body delivered by the session. Takes over {@code payload}. */
    void onResponseData(ByteBuf payload) {
        requireLoop();
        int length = payload.readableBytes();
        bytesOut += length;
        owner.parking().onHolding(streamId, length);
        if (!toEdge(() -> edge.writeContent(this, payload))) {
            payload.release();
            owner.onWritten(streamId, length);
            return;
        }
        markWritten();
    }

    /** The client's {@code CLOSE}: the response is complete. */
    void onResponseEnd() {
        requireLoop();
        responseEnded = true;
        toEdge(() -> edge.writeEnd(this));
        if (owner.session().streamState(streamId).isEmpty()) {
            finish("closed");
        }
    }

    /** The client reset the stream with {@code code}. */
    void onClientReset(ErrorCode code) {
        requireLoop();
        owner.streamReset(code, false);
        endEdge(
                code == ErrorCode.LOCAL_SERVICE_UNREACHABLE
                        ? EdgeError.LOCAL_SERVICE_UNREACHABLE
                        : EdgeError.LOCAL_SERVICE_ERROR,
                code);
        finish("reset by client " + code.code());
    }

    /** The client broke a stream rule; the session has already closed the stream, and {@code code} is sent here. */
    void onStreamError(ErrorCode code) {
        requireLoop();
        owner.sendReset(streamId, code);
        endEdge(EdgeError.LOCAL_SERVICE_ERROR, code);
        finish("reset " + code.code());
    }

    /** Flushes what was written to the edge in the session's current read burst. */
    void flushEdge() {
        flushPending = false;
        toEdge(() -> edge.flush(this));
    }

    private void markWritten() {
        if (!flushPending) {
            flushPending = true;
            owner.flushAtReadComplete(this);
        }
    }

    // Endings.

    private void onVisitorGone() {
        requireLoop();
        if (!finished) {
            reset();
            if (!responseEnded) {
                resetCode = ErrorCode.CANCELLED;
            }
            finish("visitor gone");
        }
    }

    private void onResponseTimeout() {
        if (!finished && !finalHeadSent) {
            reset();
            endEdge(EdgeError.RESPONSE_TIMEOUT, ErrorCode.CANCELLED);
            finish("response timeout");
        }
    }

    /** The request can't go on: reset the stream and answer the visitor with {@code error} if nothing has started. */
    private void fail(EdgeError error) {
        requireLoop();
        if (!finished) {
            reset();
            endEdge(error, ErrorCode.CANCELLED);
            finish("failed " + error);
        }
    }

    /** The session is ending: reset, and a page if no response has started. */
    void cancel(EdgeError error) {
        requireLoop();
        if (!finished) {
            reset();
            endEdge(error, ErrorCode.CANCELLED);
            finish("cancelled");
        }
    }

    /** Resets the stream with {@code CANCELLED} from this side, if the stream is still open. */
    private void reset() {
        if (owner.session().streamState(streamId).isPresent()) {
            owner.resetStream(streamId);
        }
    }

    /**
     * Ends the edge's half after a failure: the page if no final head has gone out, the connection cut if the response
     * started and hasn't ended, and nothing if it ended, since the edge finishes a complete response itself.
     *
     * @param code what the stream was reset with, which the log line reports for a response cut short
     */
    private void endEdge(EdgeError error, ErrorCode code) {
        if (!finalHeadSent) {
            page = error;
            status = error.status();
            toEdge(() -> edge.page(this, error));
        } else if (!responseEnded) {
            resetCode = code;
            toEdge(() -> edge.abort(this));
        }
    }

    private void finish(String ending) {
        finished = true;
        grant = 0;
        cancelResponseTimer();
        releaseHeld();
        owner.finished(this, bytesIn, bytesOut, ending);
        ExchangeLog.ended(outcome(owner.nanoTime() - openedNanos));
    }

    private ExchangeLog.Ending outcome(long totalNanos) {
        return new ExchangeLog.Ending(
                tunnel,
                host,
                open.request().method(),
                status,
                page,
                resetCode,
                bytesIn,
                bytesOut,
                headNanos,
                totalNanos);
    }

    private void cancelResponseTimer() {
        if (responseTimer != null) {
            responseTimer.cancel(false);
            responseTimer = null;
        }
    }

    /**
     * Posts {@code task} to the edge's loop, always as a task, never run inline: a write the session makes must stay
     * behind the messages it posted before it, which a direct call on a shared loop would overtake.
     *
     * @return false if the edge's loop has stopped, which ends its connection
     */
    private boolean toEdge(Runnable task) {
        try {
            edgeLoop.execute(task);
            return true;
        } catch (RejectedExecutionException stopped) {
            return false;
        }
    }

    /** For diagnosing a stall: what the request side waits for, never a byte of the body. */
    String describe() {
        return "held=" + (held == null ? 0 : held.readableBytes()) + ", grant=" + grant + ", reading=" + reading
                + ", bodyComplete=" + bodyComplete + ", closeSent=" + closeSent + ", finalHead=" + finalHeadSent
                + ", responseEnded=" + responseEnded + ", waiting="
                + owner.gate().waiting(this);
    }

    /**
     * A call off the session's loop would corrupt the session's flow state without failing anything, so every entry on
     * the session's side checks it.
     */
    private void requireLoop() {
        if (!owner.loop().inEventLoop()) {
            throw new IllegalStateException("Stream " + streamId + "'s exchange touched off its session's event loop");
        }
    }
}
