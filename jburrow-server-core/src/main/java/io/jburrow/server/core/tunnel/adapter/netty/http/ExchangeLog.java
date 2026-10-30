package io.jburrow.server.core.tunnel.adapter.netty.http;

import io.jburrow.protocol.wire.ErrorCode;
import io.jburrow.server.core.tunnel.domain.TunnelId;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The INFO line each HTTP exchange ends with, {@code docs/phase-3-http-tunnels/ARCHITECTURE.md} §Observability: the
 * tunnel, the host, the method, the status and who answered, the body bytes each way, and the time to the head and in
 * total. Never the target, a header value, a cookie or a body: a path or query string often holds a token.
 *
 * <p>{@code status} is the status the visitor got, the page's for an error page ({@code origin=edge}, with its
 * {@code code}), or {@code -} with {@code origin=-} if no response head went out, as when the visitor left first.
 * {@code reset} is the code that ended the stream before the response was complete, when no page reports it.
 */
final class ExchangeLog {

    private static final Logger log = LoggerFactory.getLogger(ExchangeLog.class);

    /** The longest method logged as sent; a longer one, which no forwarded exchange has, is logged as {@code -}. */
    private static final int MAX_METHOD = 32;

    private static final String NONE = "-";

    /** How an exchange ended, as its line reports it. */
    record Ending(
            TunnelId tunnel,
            String host,
            String method,
            int status,
            EdgeError page,
            ErrorCode reset,
            long requestBytes,
            long responseBytes,
            long headNanos,
            long totalNanos) {

        Ending {
            Objects.requireNonNull(host, "host");
            Objects.requireNonNull(method, "method");
        }
    }

    private ExchangeLog() {}

    static void ended(Ending ending) {
        if (!log.isInfoEnabled()) {
            return;
        }
        StringBuilder line = new StringBuilder("http exchange: tunnel=")
                .append(ending.tunnel() == null ? NONE : ending.tunnel().value())
                .append(" host=")
                .append(ending.host())
                .append(" method=")
                .append(ending.method().length() <= MAX_METHOD ? ending.method() : NONE)
                .append(" status=")
                .append(ending.status() > 0 ? Integer.toString(ending.status()) : NONE)
                .append(" origin=")
                .append(ending.page() != null ? "edge" : ending.status() > 0 ? "tunnel" : NONE);
        if (ending.page() != null) {
            line.append(" code=").append(ending.page().name());
        }
        if (ending.reset() != null) {
            line.append(" reset=").append(ending.reset().code());
        }
        line.append(" requestBytes=")
                .append(ending.requestBytes())
                .append(" responseBytes=")
                .append(ending.responseBytes())
                .append(" headMs=")
                .append(
                        ending.headNanos() < 0
                                ? NONE
                                : Long.toString(TimeUnit.NANOSECONDS.toMillis(ending.headNanos())))
                .append(" totalMs=")
                .append(TimeUnit.NANOSECONDS.toMillis(ending.totalNanos()));
        log.info(line.toString());
    }

    /** The line of an exchange the edge answered with {@code page} before any session took it. */
    static void answeredByEdge(String host, String method, EdgeError page) {
        ended(new Ending(null, host, method, page.status(), page, null, 0, 0, -1, 0));
    }
}
