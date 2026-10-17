package io.jburrow.server.api.instance.application;

import io.jburrow.server.api.instance.application.port.ServerInstanceStore;
import io.jburrow.server.api.instance.domain.InstanceId;
import io.jburrow.server.api.instance.domain.ServerInstance;
import java.util.Objects;

/** Describes the running instance as it was recorded at startup. */
public final class DescribeInstance {

    private final ServerInstanceStore store;
    private final InstanceId current;

    public DescribeInstance(ServerInstanceStore store, InstanceId current) {
        this.store = Objects.requireNonNull(store, "store");
        this.current = Objects.requireNonNull(current, "current");
    }

    /** @throws InstanceNotRecordedException if the startup record was never written */
    public ServerInstance execute() {
        return store.find(current).orElseThrow(() -> new InstanceNotRecordedException(current));
    }
}
