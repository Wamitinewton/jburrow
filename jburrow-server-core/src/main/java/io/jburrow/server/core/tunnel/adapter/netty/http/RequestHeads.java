package io.jburrow.server.core.tunnel.adapter.netty.http;

import io.jburrow.protocol.adapter.jackson.ControlJson;
import io.jburrow.protocol.control.BodyFraming;
import io.jburrow.protocol.control.HeaderFields;
import io.jburrow.protocol.control.HttpRequestHead;
import io.jburrow.protocol.control.Open;
import io.jburrow.protocol.control.RemoteAddress;
import io.jburrow.protocol.control.TunnelKind;
import io.jburrow.protocol.wire.ProtocolException;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Turns a request head the edge decoded into the {@code OPEN} of its stream, {@code docs/phase-3-http-tunnels/
 * ARCHITECTURE.md} §An exchange, steps 1 and 3, and §Request headers the edge sets. Connection-specific fields and
 * every forwarding field the visitor sent are removed, the edge's own forwarding fields are added from what the
 * dispatcher resolved, and the body's framing becomes {@code body}. The records the result is built from check every
 * field rule as they are constructed, so nothing unchecked reaches the wire.
 *
 * <p>Stateless apart from its settings; shared by every edge connection.
 */
final class RequestHeads {

    static final String VIA = "1.1 jburrow";

    /** The longest field value a head carries ({@code docs/protocol.md} §7.10): a longer one is {@code 431}. */
    static final int MAX_VALUE_LENGTH = 4_096;

    private static final String X_FORWARDED = "x-forwarded-";
    private static final Set<String> FORWARDING = Set.of("forwarded", "x-real-ip", "expect");
    private static final String HTTP = "http";
    private static final String HTTPS = "https";
    private static final String ASTERISK_FORM = "*";

    /** What a request head became: the {@code OPEN} to send, or the page the edge answers instead. */
    sealed interface Result {

        record Forward(Open open) implements Result {}

        record Refuse(EdgeError error) implements Result {}
    }

    private final ControlJson json;
    private final long maxRequestBody;

    RequestHeads(ControlJson json, long maxRequestBody) {
        this.json = Objects.requireNonNull(json, "json");
        this.maxRequestBody = maxRequestBody;
    }

    /**
     * The checks the edge makes before routing: an upgrade it can't pass on, a target or method it won't forward,
     * ambiguous framing, and a declared body over {@code jburrow.http.max-request-body}. Framing Netty's decoder already
     * refused never gets here.
     */
    Optional<EdgeError> check(HttpRequest request) {
        HttpHeaders headers = request.headers();
        if (headers.contains(HttpHeaderNames.UPGRADE)) {
            return Optional.of(EdgeError.UPGRADE_NOT_SUPPORTED);
        }
        if (HttpMethod.CONNECT.equals(request.method()) || !originOrAsteriskForm(request)) {
            return Optional.of(EdgeError.BAD_REQUEST);
        }
        List<String> encodings = headers.getAll(HttpHeaderNames.TRANSFER_ENCODING);
        List<String> lengths = headers.getAll(HttpHeaderNames.CONTENT_LENGTH);
        if (encodings.size() > 1
                || encodings.size() == 1 && !HttpHeaderValues.CHUNKED.contentEqualsIgnoreCase(encodings.get(0))
                || lengths.size() > 1
                || !encodings.isEmpty() && !lengths.isEmpty()) {
            return Optional.of(EdgeError.BAD_REQUEST);
        }
        if (lengths.size() == 1 && contentLength(lengths.get(0)) > maxRequestBody) {
            return Optional.of(EdgeError.REQUEST_TOO_LARGE);
        }
        return Optional.empty();
    }

    /**
     * The {@code OPEN} for {@code request}, which has passed {@link #check}.
     *
     * @param remote the visitor, as the dispatcher resolved it; also the request's {@code x-forwarded-for}
     * @param viaTrustedProxy whether the connection's peer is a trusted proxy, whose {@code x-forwarded-proto} is kept
     */
    Result open(HttpRequest request, UUID tunnelId, RemoteAddress remote, boolean viaTrustedProxy) {
        HttpHeaders headers = request.headers();
        Set<String> dropped = connectionSpecific(headers);
        List<HeaderFields.Field> fields = new ArrayList<>(headers.size() + 4);
        try {
            for (Iterator<Map.Entry<CharSequence, CharSequence>> it = headers.iteratorCharSequence(); it.hasNext(); ) {
                Map.Entry<CharSequence, CharSequence> header = it.next();
                String name = header.getKey().toString().toLowerCase(Locale.ROOT);
                if (dropped.contains(name) || FORWARDING.contains(name) || name.startsWith(X_FORWARDED)) {
                    continue;
                }
                String value = header.getValue().toString();
                if (value.length() > MAX_VALUE_LENGTH) {
                    return new Result.Refuse(EdgeError.REQUEST_HEADERS_TOO_LARGE);
                }
                fields.add(new HeaderFields.Field(name, value));
            }
            fields.add(new HeaderFields.Field("x-forwarded-for", remote.address()));
            fields.add(new HeaderFields.Field("x-forwarded-proto", proto(headers, viaTrustedProxy)));
            fields.add(new HeaderFields.Field("x-forwarded-host", headers.get(HttpHeaderNames.HOST)));
            fields.add(new HeaderFields.Field("via", VIA));
            if (fields.size() > HeaderFields.MAX_FIELDS) {
                return new Result.Refuse(EdgeError.REQUEST_HEADERS_TOO_LARGE);
            }
            HttpRequestHead head = new HttpRequestHead(
                    request.method().name(), request.uri(), new HeaderFields(fields), framing(headers));
            Open open = new Open(tunnelId, TunnelKind.HTTP, remote, head);
            return fits(open) ? new Result.Forward(open) : new Result.Refuse(EdgeError.REQUEST_HEADERS_TOO_LARGE);
        } catch (ProtocolException invalid) {
            return new Result.Refuse(EdgeError.BAD_REQUEST);
        }
    }

    /** Whether {@code open} encodes within a control payload, which {@link ControlJson#write} refuses past. */
    private boolean fits(Open open) {
        try {
            json.write(open);
            return true;
        } catch (IllegalStateException tooLarge) {
            return false;
        }
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

    /** {@code x-forwarded-proto} from a trusted proxy if it names a scheme the edge knows, otherwise {@code http}. */
    private static String proto(HttpHeaders headers, boolean viaTrustedProxy) {
        if (!viaTrustedProxy) {
            return HTTP;
        }
        List<String> values = headers.getAll(X_FORWARDED + "proto");
        if (values.size() != 1) {
            return HTTP;
        }
        String scheme = values.get(0).strip().toLowerCase(Locale.ROOT);
        return scheme.equals(HTTPS) ? HTTPS : HTTP;
    }

    private static BodyFraming framing(HttpHeaders headers) {
        if (headers.contains(HttpHeaderNames.TRANSFER_ENCODING)) {
            return BodyFraming.CHUNKED;
        }
        String length = headers.get(HttpHeaderNames.CONTENT_LENGTH);
        return length == null || contentLength(length) == 0 ? BodyFraming.EMPTY : BodyFraming.LENGTH;
    }

    private static boolean originOrAsteriskForm(HttpRequest request) {
        String target = request.uri();
        return target.startsWith("/") || target.equals(ASTERISK_FORM) && HttpMethod.OPTIONS.equals(request.method());
    }

    /** The decoder has already required decimal digits; a value too long for a {@code long} is past any limit. */
    private static long contentLength(String value) {
        try {
            return Long.parseLong(value.strip());
        } catch (NumberFormatException tooLong) {
            return Long.MAX_VALUE;
        }
    }
}
