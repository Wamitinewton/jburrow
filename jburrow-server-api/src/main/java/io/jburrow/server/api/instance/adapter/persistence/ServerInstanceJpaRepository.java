package io.jburrow.server.api.instance.adapter.persistence;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ServerInstanceJpaRepository extends JpaRepository<ServerInstanceEntity, UUID> {}
