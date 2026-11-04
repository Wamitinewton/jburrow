package io.jburrow.server.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import io.jburrow.protocol.adapter.netty.Frame;
import io.jburrow.protocol.control.ClientInfo;
import io.jburrow.protocol.control.Hello;
import io.jburrow.protocol.control.Register;
import io.jburrow.protocol.control.RegisteredTunnel;
import io.jburrow.protocol.control.TunnelKind;
import io.jburrow.protocol.control.TunnelRequest;
import io.jburrow.protocol.testsupport.ScriptedPeer;
import io.jburrow.protocol.wire.ErrorCode;
import io.jburrow.protocol.wire.ProtocolVersion;
import io.jburrow.server.JBurrowServerApplication;
import io.jburrow.server.api.instance.domain.InstanceId;
import io.jburrow.server.core.tunnel.adapter.netty.edge.EdgeServer;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;

/**
 * A server process's names and ports outlive it ({@code docs/phase-4-staying-connected/ARCHITECTURE.md} §Durable
 * leases): one application registers tunnels and is closed, and the next one, on the same Postgres, holds what the first
 * wrote for its owner before it accepts a connection. The first's drain flushes its claims as holds once every session
 * has ended (§The drain), so even claims no periodic write saw survive it. Both run beside this class's own context, so
 * the cached test context is never closed under the context cache.
 */
@TestPropertySource(properties = "jburrow.tcp.port-range=32000-32009")
class LeaseRestartIntegrationTest extends TunnelTestSupport {

    /** The two applications' range, apart from this class's own context. */
    private static final String RESTARTED_PORTS = "--jburrow.tcp.port-range=32010-32019";

    /** {@code jburrow.session.resume-window}'s default, which the test profile keeps. */
    private static final Duration RESUME_WINDOW = Duration.ofSeconds(120);

    @Autowired
    private JdbcConnectionDetails db;

    @Autowired
    private Environment environment;

    @Autowired
    private JdbcClient jdbc;

    @Test
    void aRestartedServerHoldsTheLastProcessesNamesAndPortsForTheirOwner() throws Exception {
        ConfigurableApplicationContext first = closeAfterTest(startServer());
        URI firstServer = connectUriOf(first);
        RegisteredTunnel web = register(firstServer, ALICE_TOKEN, httpRequest(null));
        RegisteredTunnel ssh = register(firstServer, ALICE_TOKEN, tcpRequest(null));
        String name = web.publicHost().substring(0, web.publicHost().indexOf('.'));
        await(
                "the first process has written both claims",
                () -> leasesOf(first).containsAll(List.of("name " + name, "port " + ssh.publicPort())));

        first.close();

        ConfigurableApplicationContext second = closeAfterTest(startServer());
        URI secondServer = connectUriOf(second);
        assertThat(refusal(secondServer, BOB_TOKEN, httpRequest(name))).isEqualTo(ErrorCode.SUBDOMAIN_UNAVAILABLE);
        assertThat(refusal(secondServer, BOB_TOKEN, tcpRequest(ssh.publicPort())))
                .isEqualTo(ErrorCode.PORT_UNAVAILABLE);
        assertThat(register(secondServer, ALICE_TOKEN, httpRequest(name)).publicHost())
                .isEqualTo(web.publicHost());
        assertThat(register(secondServer, ALICE_TOKEN, tcpRequest(ssh.publicPort()))
                        .publicPort())
                .isEqualTo(ssh.publicPort());
    }

    @Test
    void aDrainedStopFlushesEveryClaimAsAHoldEndingAWindowAfterTheStop() throws Exception {
        ConfigurableApplicationContext first = closeAfterTest(startServer());
        URI firstServer = connectUriOf(first);
        RegisteredTunnel web = register(firstServer, ALICE_TOKEN, httpRequest(null));
        RegisteredTunnel ssh = register(firstServer, ALICE_TOKEN, tcpRequest(null));
        String name = web.publicHost().substring(0, web.publicHost().indexOf('.'));
        UUID firstInstance = first.getBean(InstanceId.class).value();
        Instant stopping = Instant.now();

        first.close();

        Instant stopped = Instant.now();
        List<Lease> leases = jdbc.sql(
                        "select kind || ' ' || lease_key as lease, active, expires_at from jburrow.tunnel_lease"
                                + " where instance_id = ?")
                .param(firstInstance)
                .query((row, index) -> new Lease(
                        row.getString("lease"),
                        row.getBoolean("active"),
                        row.getTimestamp("expires_at").toInstant()))
                .list();
        assertThat(leases).extracting(Lease::key).containsExactlyInAnyOrder("name " + name, "port " + ssh.publicPort());
        assertThat(leases).allSatisfy(lease -> {
            assertThat(lease.active()).as("%s is held", lease.key()).isFalse();
            assertThat(lease.expiresAt()).isBetween(stopping.plus(RESUME_WINDOW), stopped.plus(RESUME_WINDOW));
        });

        ConfigurableApplicationContext second = closeAfterTest(startServer());
        URI secondServer = connectUriOf(second);
        assertThat(refusal(secondServer, BOB_TOKEN, httpRequest(name))).isEqualTo(ErrorCode.SUBDOMAIN_UNAVAILABLE);
        assertThat(refusal(secondServer, BOB_TOKEN, tcpRequest(ssh.publicPort())))
                .isEqualTo(ErrorCode.PORT_UNAVAILABLE);
        assertThat(register(secondServer, ALICE_TOKEN, httpRequest(name)).publicHost())
                .isEqualTo(web.publicHost());
    }

    /** One row of {@code tunnel_lease}: {@code kind key}, whether it was active, and when it stops counting. */
    private record Lease(String key, boolean active, Instant expiresAt) {}

    private ConfigurableApplicationContext startServer() {
        return new SpringApplicationBuilder(JBurrowServerApplication.class)
                .profiles("test")
                .run(
                        "--spring.datasource.url=" + db.getJdbcUrl(),
                        "--spring.datasource.username=" + db.getUsername(),
                        "--spring.datasource.password=" + db.getPassword(),
                        "--spring.mail.host=" + environment.getProperty("spring.mail.host"),
                        "--spring.mail.port=" + environment.getProperty("spring.mail.port"),
                        "--server.port=0",
                        "--management.server.port=0",
                        "--jburrow.edge.port=0",
                        RESTARTED_PORTS);
    }

    private static URI connectUriOf(ConfigurableApplicationContext context) {
        return URI.create(
                "ws://localhost:" + context.getBean(EdgeServer.class).boundPort() + ScriptedPeer.CONNECT_PATH);
    }

    /** {@code kind key} for each of the context's rows. */
    private List<String> leasesOf(ConfigurableApplicationContext context) {
        return jdbc.sql("select kind || ' ' || lease_key from jburrow.tunnel_lease where instance_id = ?")
                .param(context.getBean(InstanceId.class).value())
                .query(String.class)
                .list();
    }

    /** Registers {@code tunnel} in a session of its own, which stays open until the test ends. */
    private RegisteredTunnel register(URI server, String token, TunnelRequest tunnel) {
        ScriptedPeer peer = helloed(server);
        peer.send(new Frame.Register(new Register(token, List.of(tunnel))));
        return peer.expect(Frame.Registered.class, TIMEOUT).message().tunnels().getFirst();
    }

    private ErrorCode refusal(URI server, String token, TunnelRequest tunnel) {
        ScriptedPeer peer = helloed(server);
        peer.send(new Frame.Register(new Register(token, List.of(tunnel))));
        return peer.expect(Frame.Error.class, TIMEOUT).body().code();
    }

    private ScriptedPeer helloed(URI server) {
        ScriptedPeer peer = closeAfterTest(ScriptedPeer.connect(server, ProtocolVersion.CURRENT));
        peer.send(new Frame.Hello(new Hello(
                new ClientInfo("jburrow-e2e", "1.0", "linux", "amd64"), List.of("http"), ScriptedPeer.CLIENT_WINDOWS)));
        peer.expect(Frame.ServerHello.class, TIMEOUT);
        return peer;
    }

    private static TunnelRequest httpRequest(String subdomain) {
        return new TunnelRequest("web", TunnelKind.HTTP, subdomain, null);
    }

    private static TunnelRequest tcpRequest(Integer remotePort) {
        return new TunnelRequest("ssh", TunnelKind.TCP, remotePort);
    }
}
