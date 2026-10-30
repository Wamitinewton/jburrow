package io.jburrow.server.core.routing.application.port;

import io.jburrow.protocol.control.SubdomainName;
import java.util.Optional;

/** Finds the tunnel serving a name. Used by the edge on every request; never blocks. */
public interface RouteLookup<T> {

    /** The tunnel with an active claim on {@code name}; a held name routes nowhere. */
    Optional<T> find(SubdomainName name);
}
