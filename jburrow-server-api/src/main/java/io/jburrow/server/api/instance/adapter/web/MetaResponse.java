package io.jburrow.server.api.instance.adapter.web;

import io.jburrow.server.api.instance.domain.ServerInstance;
import java.time.Instant;

/** The public view of the running instance. Profiles are left out on purpose. */
public record MetaResponse(String instanceId, String version, String commit, Instant startedAt) {

    public static MetaResponse from(ServerInstance instance) {
        return new MetaResponse(
                instance.id().toString(),
                instance.build().version(),
                instance.build().commit(),
                instance.startedAt());
    }
}
