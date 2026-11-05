package io.jburrow.server.core.routing.application.port;

import io.jburrow.protocol.control.SubdomainName;
import java.util.Objects;

/**
 * A requested name, claimed.
 *
 * @param reclaimed whether it was held for the same principal, so the claim took back a name its owner had a moment ago
 */
public record Claimed(SubdomainName name, boolean reclaimed) {

    public Claimed {
        Objects.requireNonNull(name, "name");
    }
}
