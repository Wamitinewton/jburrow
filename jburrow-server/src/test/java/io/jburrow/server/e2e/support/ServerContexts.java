package io.jburrow.server.e2e.support;

import io.jburrow.protocol.testsupport.ScriptedPeer;
import io.jburrow.server.JBurrowServerApplication;
import io.jburrow.server.api.instance.domain.InstanceId;
import io.jburrow.server.core.lease.application.port.LeaseRecord;
import io.jburrow.server.core.lease.application.port.LeaseStore;
import io.jburrow.server.core.tunnel.adapter.netty.edge.EdgeServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.env.Environment;

/**
 * One server process after another, in this JVM, on the same fixed ports and the same Postgres: what a deploy, or a
 * crash and a restart, looks like to the clients and to the database.
 *
 * <p>Clients connect through a front proxy ({@link #connectUri()}), which stands where the ingress stands in
 * production: while no process runs it resets every new connection, as an ingress with no endpoint refuses, and it lets
 * clients through again only once the next process is up and the test has had its turn at it ({@link #start(Consumer)}).
 * The process itself listens on fixed edge, API and tunnel ports below the ephemeral range, so the next one binds the
 * same ports the last one released.
 *
 * <p>{@link #stop()} closes the process the way {@code SIGTERM} does, through the drain. {@link #kill()} stands in for
 * a crash: from then on nothing the process does reaches Postgres, every control connection is reset without a word,
 * and only then is it closed, so no {@code GOAWAY}, no final flush and no write after the last periodic one survive it.
 *
 * <p>Each process seeds what every earlier one wrote, whichever class ran it, so every user of this class names a block
 * of ports of its own: {@link Ports}.
 */
public final class ServerContexts implements AutoCloseable {

    /**
     * Ten tunnel ports from {@code first}, the edge's port at {@code first + 10} and the API's at {@code first + 11}.
     * All must be below 32768 and in no other test's range.
     */
    public record Ports(int first) {

        public Ports {
            if (first < 1024 || first + 11 >= 32_768) {
                throw new IllegalArgumentException("The block must sit below the ephemeral range: " + first);
            }
        }

        public String tunnelRange() {
            return first + "-" + (first + 9);
        }

        public int edge() {
            return first + 10;
        }

        public int api() {
            return first + 11;
        }
    }

    private final List<String> arguments;
    private final Ports ports;
    private final CuttingProxy front;
    private ConfigurableApplicationContext current;
    private SeverableLeaseStore leases;

    private ServerContexts(List<String> arguments, Ports ports) {
        this.arguments = arguments;
        this.ports = ports;
        this.front = CuttingProxy.to(ports.edge());
    }

    /**
     * Processes on {@code db} and the test context's Mailpit, with the {@code test} profile, {@code ports}, and every
     * one of {@code properties} ({@code key=value}).
     */
    public static ServerContexts create(
            JdbcConnectionDetails db, Environment environment, Ports ports, String... properties) {
        List<String> arguments = new ArrayList<>(List.of(
                "--spring.datasource.url=" + db.getJdbcUrl(),
                "--spring.datasource.username=" + db.getUsername(),
                "--spring.datasource.password=" + db.getPassword(),
                "--spring.mail.host=" + environment.getProperty("spring.mail.host"),
                "--spring.mail.port=" + environment.getProperty("spring.mail.port"),
                "--server.port=" + ports.api(),
                "--management.server.port=0",
                "--jburrow.edge.port=" + ports.edge(),
                "--jburrow.tcp.port-range=" + ports.tunnelRange()));
        for (String property : properties) {
            arguments.add("--" + property);
        }
        return new ServerContexts(List.copyOf(arguments), ports);
    }

    /** Where clients connect: through the front, so they reach whichever process runs. */
    public URI connectUri() {
        return URI.create("ws://localhost:" + front.port() + ScriptedPeer.CONNECT_PATH);
    }

    /** Straight to the running process's edge, past the front. */
    public URI directConnectUri() {
        return URI.create("ws://localhost:" + ports.edge() + ScriptedPeer.CONNECT_PATH);
    }

    /** Starts the next process and lets clients through. */
    public ConfigurableApplicationContext start() {
        return start(direct -> {});
    }

    /**
     * Starts the next process; runs {@code beforeClients} with its {@link #directConnectUri()} while the front still
     * refuses clients; then lets them through.
     */
    public synchronized ConfigurableApplicationContext start(Consumer<URI> beforeClients) {
        if (current != null) {
            throw new IllegalStateException("A process is already running");
        }
        SeverableLeaseStore store = new SeverableLeaseStore();
        current = new SpringApplicationBuilder(JBurrowServerApplication.class)
                .profiles("test")
                .initializers(context -> ((GenericApplicationContext) context)
                        .registerBean("severableLeaseStore", BeanPostProcessor.class, store::wrapper))
                .run(arguments.toArray(String[]::new));
        leases = store;
        beforeClients.accept(directConnectUri());
        front.forward();
        return current;
    }

    /** Stops the running process through the drain, as {@code SIGTERM} does. Returns once it has closed. */
    public synchronized void stop() {
        ConfigurableApplicationContext stopping = running();
        front.refuse();
        current = null;
        stopping.close();
    }

    /**
     * Ends the running process as a crash does: its lease writes stop reaching Postgres ({@link #freezeLeases()}), every
     * control connection is reset, and the process is closed with nobody left to drain.
     */
    public synchronized void kill() {
        ConfigurableApplicationContext killed = running();
        freezeLeases();
        front.refuse();
        front.cut();
        current = null;
        killed.close();
    }

    /**
     * From now on, no lease write of the running process reaches Postgres, a write in flight excepted, as if the
     * process had lost its database: what is in the table is what the next process seeds, if this one crashes.
     */
    public synchronized void freezeLeases() {
        running();
        leases.sever();
    }

    /** The running process. */
    public synchronized ConfigurableApplicationContext running() {
        if (current == null) {
            throw new IllegalStateException("No process is running");
        }
        return current;
    }

    /** The running process's instance id, the one its lease rows carry. */
    public UUID instanceId() {
        return running().getBean(InstanceId.class).value();
    }

    public EdgeServer edge() {
        return running().getBean(EdgeServer.class);
    }

    /** Stops a process still running, through the drain, and the front. */
    @Override
    public synchronized void close() {
        try {
            if (current != null) {
                stop();
            }
        } finally {
            try {
                front.close();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    /**
     * The process's {@link LeaseStore}, which it writes through, with a switch that makes every write fail from then on.
     * A write already past the switch completes, as one already sent to the database would.
     */
    private static final class SeverableLeaseStore {

        private volatile boolean severed;

        void sever() {
            severed = true;
        }

        /**
         * Wraps the store the process writes through. A bean, not added to the factory directly, so it runs after the
         * proxies that give the store its transactions, and wraps those.
         */
        BeanPostProcessor wrapper() {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String name) {
                    return bean instanceof LeaseStore store ? wrap(store) : bean;
                }
            };
        }

        private LeaseStore wrap(LeaseStore store) {
            Objects.requireNonNull(store, "store");
            return new LeaseStore() {
                @Override
                public void replace(UUID instance, List<LeaseRecord> records) {
                    requireConnected();
                    store.replace(instance, records);
                }

                @Override
                public List<LeaseRecord> loadUnexpired(Instant now) {
                    requireConnected();
                    return store.loadUnexpired(now);
                }

                @Override
                public int purgeExpiredBefore(Instant cutoff) {
                    requireConnected();
                    return store.purgeExpiredBefore(cutoff);
                }
            };
        }

        private void requireConnected() {
            if (severed) {
                throw new IllegalStateException("This server process has crashed; nothing it writes is kept");
            }
        }
    }
}
