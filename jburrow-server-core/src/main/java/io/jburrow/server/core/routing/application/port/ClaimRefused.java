package io.jburrow.server.core.routing.application.port;

import java.util.Objects;

/**
 * A name that can't be claimed. Registration turns it into a protocol code; this feature knows none. The message never
 * names the subdomain, since names stay out of logs. A refusal is an ordinary outcome a client can provoke, so it
 * carries no stack trace.
 */
public final class ClaimRefused extends RuntimeException {

    public enum Reason {
        /** Reserved, or containing a blocked term. */
        FORBIDDEN,
        /** Claimed by another tunnel, or held for another principal. */
        UNAVAILABLE
    }

    private final Reason reason;

    public ClaimRefused(Reason reason) {
        super(
                switch (Objects.requireNonNull(reason, "reason")) {
                    case FORBIDDEN -> "The subdomain is reserved or blocked";
                    case UNAVAILABLE -> "The subdomain is taken";
                },
                null,
                false,
                false);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
