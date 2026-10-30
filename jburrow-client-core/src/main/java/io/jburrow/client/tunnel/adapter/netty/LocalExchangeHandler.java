package io.jburrow.client.tunnel.adapter.netty;

import io.jburrow.client.tunnel.domain.HostHeader;
import io.jburrow.client.tunnel.domain.LocalTarget;
import io.jburrow.protocol.adapter.jackson.ControlJson;
import io.jburrow.protocol.control.BodyFraming;
import io.jburrow.protocol.control.HeaderFields;
import io.jburrow.protocol.control.HttpRequestHead;
import io.jburrow.protocol.control.HttpResponseHead;
import io.jburrow.protocol.wire.ErrorCode;
import io.jburrow.protocol.wire.FrameLimits;
import io.jburrow.protocol.wire.ProtocolException;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpDecoderConfig;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.util.ReferenceCountUtil;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.Set;

/**
 * One HTTP exchange on its local connection ({@code docs/phase-3-http-tunnels/ARCHITECTURE.md} §The client): writes the
 * request from the {@code OPEN}'s head and turns the local service's response into the stream's {@code HEADERS},
 * body bytes and end, for {@link LocalConnector} to send. Between {@link HttpClientCodec} and the stream's
 * {@link LocalRelayHandler}, which still hears the connection's events.
 *
 * <p>The request is the head's fields in order, with {@code expect} left out (the edge answers it) and, with
 * {@link HostHeader#REWRITE}, {@code host} replaced by the local target. Its framing is the head's {@code body}:
 * {@code content-length} is in the fields for {@code "length"}, {@code transfer-encoding: chunked} is added for
 * {@code "chunked"}. Body bytes written to the channel become content, and {@link LastHttpContent} ends it.
 *
 * <p>A response head loses its connection-specific fields, and the names {@code connection} lists, and its names are
 * lower-cased. Its {@code body} is {@code "empty"} for a response to {@code HEAD} and for {@code 204} and {@code 304};
 * {@code "length"} with one valid {@code content-length}; otherwise {@code "chunked"}, which covers chunked and
 * close-delimited bodies alike. Values keep the bytes Netty decoded, each as the code point of the same number, so
 * {@code obs-text} survives. A response that isn't valid HTTP/1.1, a {@code 101}, a head that doesn't fit a control
 * payload, or a transfer coding other than {@code chunked} fails the exchange as the local service's error.
 *
 * <p>The decoder never produces more body bytes in a read than the read took, so a read sized by the {@link ReadGate}
 * still fits its credit; {@code HEADERS} isn't flow-controlled. Trailers are dropped.
 *
 * <p>Confined to the session's event loop.
 */
final class LocalExchangeHandler extends ChannelDuplexHandler {

    static final int MAX_INITIAL_LINE_LENGTH = 4_096;
    static final int MAX_HEADER_SIZE = 12 * 1024;

    private static final int FIRST_FINAL_STATUS = 200;
    private static final int SWITCHING_PROTOCOLS = 101;
    private static final int NO_CONTENT = 204;
    private static final int NOT_MODIFIED = 304;
    private static final String EXPECT = "expect";
    private static final String HOST = "host";

    private final LocalConnector connector;
    private final LocalRelayHandler stream;
    private final HttpRequestHead request;
    private final HostHeader hostHeader;
    private final LocalTarget target;
    private final ControlJson json;

    /** A {@code 1xx} head was passed on, and its empty end, which isn't the response's, comes next. */
    private boolean informationalEndPending;

    private int status;
    private boolean responseEnded;

    LocalExchangeHandler(
            LocalConnector connector,
            LocalRelayHandler stream,
            HttpRequestHead request,
            HostHeader hostHeader,
            LocalTarget target,
            ControlJson json) {
        this.connector = Objects.requireNonNull(connector, "connector");
        this.stream = Objects.requireNonNull(stream, "stream");
        this.request = Objects.requireNonNull(request, "request");
        this.hostHeader = Objects.requireNonNull(hostHeader, "hostHeader");
        this.target = Objects.requireNonNull(target, "target");
        this.json = Objects.requireNonNull(json, "json");
    }

    /** The codec in front of this handler: header validation on, and a missing response is an error. */
    static HttpClientCodec newCodec() {
        HttpDecoderConfig config = new HttpDecoderConfig()
                .setMaxInitialLineLength(MAX_INITIAL_LINE_LENGTH)
                .setMaxHeaderSize(MAX_HEADER_SIZE)
                .setMaxChunkSize(FrameLimits.MAX_DATA_PAYLOAD)
                .setValidateHeaders(true);
        return new HttpClientCodec(config, false, true);
    }

    HttpRequestHead request() {
        return request;
    }

    /** The final response's status, once its head was sent. */
    OptionalInt status() {
        return status == 0 ? OptionalInt.empty() : OptionalInt.of(status);
    }

    /**
     * Whether the local service's response has ended, even if its last bytes still wait for credit: what the
     * connection does after that can't fail the exchange.
     */
    boolean responseEnded() {
        return responseEnded;
    }

    /** The request head for the local service. */
    HttpRequest requestHead() {
        HttpHeaders headers = new DefaultHttpHeaders();
        for (HeaderFields.Field field : request.headers().fields()) {
            if (field.name().equals(EXPECT)) {
                continue;
            }
            boolean rewrite = hostHeader == HostHeader.REWRITE && field.name().equals(HOST);
            headers.add(field.name(), rewrite ? target.toString() : field.value());
        }
        if (request.body() == BodyFraming.CHUNKED) {
            headers.add(HttpHeaderNames.TRANSFER_ENCODING, HttpHeaderValues.CHUNKED);
        }
        return new DefaultHttpRequest(
                HttpVersion.HTTP_1_1, HttpMethod.valueOf(request.method()), request.target(), headers);
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
        ctx.write(msg instanceof ByteBuf bytes ? new DefaultHttpContent(bytes) : msg, promise);
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (responseEnded || !(msg instanceof HttpObject object)) {
            ReferenceCountUtil.release(msg);
            return;
        }
        if (object.decoderResult().isFailure()) {
            ReferenceCountUtil.release(msg);
            // The cause's message can quote the response, so only its type is reported.
            connector.onLocalServiceError(
                    stream,
                    "an invalid response: "
                            + object.decoderResult().cause().getClass().getSimpleName());
            return;
        }
        if (msg instanceof HttpResponse response && !onResponse(response)) {
            ReferenceCountUtil.release(msg);
            return;
        }
        if (msg instanceof HttpContent content) {
            onContent(content);
        }
    }

    /** Passes the head on; false if it failed the exchange instead. */
    private boolean onResponse(HttpResponse response) {
        int code = response.status().code();
        if (code == SWITCHING_PROTOCOLS) {
            connector.onLocalServiceError(stream, "a 101 response, which the tunnel doesn't carry");
            return false;
        }
        HttpResponseHead head;
        try {
            head = responseHead(response, request.method().equals(HttpMethod.HEAD.name()));
        } catch (ProtocolException invalid) {
            connector.onLocalServiceError(stream, "a response head the protocol refuses: " + invalid.getMessage());
            return false;
        }
        if (!fits(head)) {
            connector.onLocalServiceError(stream, "a response head over " + FrameLimits.MAX_CONTROL_PAYLOAD + " bytes");
            return false;
        }
        if (head.informational()) {
            informationalEndPending = true;
        } else {
            status = code;
        }
        connector.onLocalHead(stream, head);
        return true;
    }

    /** Takes over {@code content}: its bytes go to the stream, and its end, if it is the last, ends the response. */
    private void onContent(HttpContent content) {
        boolean last = content instanceof LastHttpContent;
        if (informationalEndPending) {
            content.release();
            informationalEndPending = !last;
            return;
        }
        ByteBuf bytes = content.content();
        if (bytes.isReadable()) {
            connector.onLocalData(stream, bytes);
        } else {
            content.release();
        }
        if (last) {
            responseEnded = true;
            connector.onLocalResponseEnd(stream);
        }
    }

    /**
     * The head of {@code response} for {@code HEADERS}.
     *
     * @throws ProtocolException if a field breaks the protocol's rules, or the framing isn't one the tunnel carries
     */
    static HttpResponseHead responseHead(HttpResponse response, boolean answersHead) {
        HttpHeaders headers = response.headers();
        Set<String> dropped = connectionSpecific(headers);
        List<HeaderFields.Field> fields = new ArrayList<>(headers.size());
        for (Iterator<Map.Entry<CharSequence, CharSequence>> it = headers.iteratorCharSequence(); it.hasNext(); ) {
            Map.Entry<CharSequence, CharSequence> header = it.next();
            String name = header.getKey().toString().toLowerCase(Locale.ROOT);
            if (!dropped.contains(name)) {
                fields.add(new HeaderFields.Field(name, header.getValue().toString()));
            }
        }
        HeaderFields kept = new HeaderFields(fields);
        int code = response.status().code();
        if (code < FIRST_FINAL_STATUS) {
            return new HttpResponseHead(code, kept, null);
        }
        return new HttpResponseHead(code, kept, framing(headers, kept, code, answersHead));
    }

    private static BodyFraming framing(HttpHeaders headers, HeaderFields kept, int code, boolean answersHead) {
        if (answersHead || code == NO_CONTENT || code == NOT_MODIFIED) {
            return BodyFraming.EMPTY;
        }
        List<String> codings = headers.getAll(HttpHeaderNames.TRANSFER_ENCODING);
        if (!codings.isEmpty()) {
            if (codings.size() != 1
                    || !HttpHeaderValues.CHUNKED.contentEqualsIgnoreCase(
                            codings.getFirst().strip())) {
                throw ProtocolException.session(ErrorCode.VALIDATION_FAILED, "transfer-encoding must be chunked alone");
            }
            return BodyFraming.CHUNKED;
        }
        return kept.contentLength().isPresent() ? BodyFraming.LENGTH : BodyFraming.CHUNKED;
    }

    /** {@code connection}, the other connection-specific fields, and every name {@code connection} lists. */
    private static Set<String> connectionSpecific(HttpHeaders headers) {
        Set<String> names = new HashSet<>(HeaderFields.CONNECTION_SPECIFIC);
        for (String value : headers.getAll(HttpHeaderNames.CONNECTION)) {
            for (String option : value.split(",", -1)) {
                String name = option.strip().toLowerCase(Locale.ROOT);
                if (!name.isEmpty()) {
                    names.add(name);
                }
            }
        }
        return names;
    }

    /** Whether {@code head} encodes within a control payload, which {@link ControlJson#write} refuses past. */
    private boolean fits(HttpResponseHead head) {
        try {
            json.write(head);
            return true;
        } catch (IllegalStateException tooLarge) {
            return false;
        }
    }
}
