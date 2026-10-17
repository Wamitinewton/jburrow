package io.jburrow.server.api.instance.application.port;

import io.jburrow.server.api.instance.domain.InstanceId;
import io.jburrow.server.api.instance.domain.ServerInstance;
import java.util.Optional;

/** Durable record of every server start. */
public interface ServerInstanceStore {

    void save(ServerInstance instance);

    Optional<ServerInstance> find(InstanceId id);
}
