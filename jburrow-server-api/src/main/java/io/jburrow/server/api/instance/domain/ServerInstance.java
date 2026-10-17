package io.jburrow.server.api.instance.domain;

import java.time.Instant;
import java.util.Objects;

/** One recorded start of the server. */
public record ServerInstance(InstanceId id, BuildIdentity build, Instant startedAt) {

    public ServerInstance {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(build, "build");
        Objects.requireNonNull(startedAt, "startedAt");
    }
}
