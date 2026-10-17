package io.jburrow.server.api.instance.adapter.persistence;

import io.jburrow.server.api.instance.application.port.ServerInstanceStore;
import io.jburrow.server.api.instance.domain.InstanceId;
import io.jburrow.server.api.instance.domain.ServerInstance;
import java.util.Objects;
import java.util.Optional;

/** {@link ServerInstanceStore} on the {@code jburrow.server_instance} table. */
public class JpaServerInstanceStore implements ServerInstanceStore {

    private final ServerInstanceJpaRepository repository;

    public JpaServerInstanceStore(ServerInstanceJpaRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository");
    }

    @Override
    public void save(ServerInstance instance) {
        repository.save(ServerInstanceEntity.fromDomain(instance));
    }

    @Override
    public Optional<ServerInstance> find(InstanceId id) {
        return repository.findById(id.value()).map(ServerInstanceEntity::toDomain);
    }
}
