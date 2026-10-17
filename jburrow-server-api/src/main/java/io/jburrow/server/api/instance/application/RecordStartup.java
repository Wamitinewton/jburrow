package io.jburrow.server.api.instance.application;

import io.jburrow.server.api.instance.application.port.BuildIdentitySource;
import io.jburrow.server.api.instance.application.port.ServerInstanceStore;
import io.jburrow.server.api.instance.domain.InstanceId;
import io.jburrow.server.api.instance.domain.ServerInstance;
import java.time.Clock;
import java.util.Objects;

/** Records the current start of this server. Runs once, when the application is ready. */
public final class RecordStartup {

    private final ServerInstanceStore store;
    private final BuildIdentitySource identity;
    private final Clock clock;
    private final InstanceId current;

    public RecordStartup(ServerInstanceStore store, BuildIdentitySource identity, Clock clock, InstanceId current) {
        this.store = Objects.requireNonNull(store, "store");
        this.identity = Objects.requireNonNull(identity, "identity");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.current = Objects.requireNonNull(current, "current");
    }

    public ServerInstance execute() {
        ServerInstance instance = new ServerInstance(current, identity.current(), clock.instant());
        store.save(instance);
        return instance;
    }
}
