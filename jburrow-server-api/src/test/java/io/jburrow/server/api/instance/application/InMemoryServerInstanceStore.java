package io.jburrow.server.api.instance.application;

import io.jburrow.server.api.instance.application.port.ServerInstanceStore;
import io.jburrow.server.api.instance.domain.InstanceId;
import io.jburrow.server.api.instance.domain.ServerInstance;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

public final class InMemoryServerInstanceStore implements ServerInstanceStore {

    private final Map<InstanceId, ServerInstance> instances = new HashMap<>();

    @Override
    public void save(ServerInstance instance) {
        instances.put(instance.id(), instance);
    }

    @Override
    public Optional<ServerInstance> find(InstanceId id) {
        return Optional.ofNullable(instances.get(id));
    }

    public int size() {
        return instances.size();
    }

    public void clear() {
        instances.clear();
    }
}
