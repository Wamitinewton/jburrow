package io.jburrow.server.api.instance.adapter.persistence;

import io.jburrow.server.api.instance.domain.BuildIdentity;
import io.jburrow.server.api.instance.domain.InstanceId;
import io.jburrow.server.api.instance.domain.ServerInstance;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Arrays;
import java.util.UUID;

@Entity
@Table(name = "server_instance")
public class ServerInstanceEntity {

    private static final String PROFILE_SEPARATOR = ",";

    @Id
    @Column(name = "instance_id")
    private UUID instanceId;

    @Column(name = "version", nullable = false)
    private String version;

    @Column(name = "git_commit", nullable = false)
    private String gitCommit;

    @Column(name = "profiles", nullable = false)
    private String profiles;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    protected ServerInstanceEntity() {}

    private ServerInstanceEntity(
            UUID instanceId, String version, String gitCommit, String profiles, Instant startedAt) {
        this.instanceId = instanceId;
        this.version = version;
        this.gitCommit = gitCommit;
        this.profiles = profiles;
        this.startedAt = startedAt;
    }

    public static ServerInstanceEntity fromDomain(ServerInstance instance) {
        BuildIdentity build = instance.build();
        return new ServerInstanceEntity(
                instance.id().value(),
                build.version(),
                build.commit(),
                String.join(PROFILE_SEPARATOR, build.profiles()),
                instance.startedAt());
    }

    public ServerInstance toDomain() {
        return new ServerInstance(
                new InstanceId(instanceId),
                new BuildIdentity(version, gitCommit, Arrays.asList(profiles.split(PROFILE_SEPARATOR))),
                startedAt);
    }
}
