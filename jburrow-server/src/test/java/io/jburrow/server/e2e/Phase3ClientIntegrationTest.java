package io.jburrow.server.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import io.jburrow.client.tunnel.domain.TunnelClosed;
import io.jburrow.protocol.adapter.netty.Frame;
import io.jburrow.protocol.control.ClientInfo;
import io.jburrow.protocol.control.Hello;
import io.jburrow.protocol.control.Register;
import io.jburrow.protocol.control.Registered;
import io.jburrow.protocol.control.TunnelKind;
import io.jburrow.protocol.control.TunnelRequest;
import io.jburrow.protocol.testsupport.ScriptedPeer;
import io.jburrow.protocol.wire.Capability;
import io.jburrow.protocol.wire.ErrorCode;
import io.jburrow.protocol.wire.ProtocolVersion;
import io.jburrow.server.JBurrowServerApplication;
import io.jburrow.server.core.tunnel.adapter.netty.edge.EdgeServer;
import java.net.URI;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.test.context.TestPropertySource;

/**
 * A client that doesn't offer {@code resume}, as a phase 3 CLI doesn't, sees a server that drains exactly as one that
 * stopped at once ({@code docs/phase-4-staying-connected/ARCHITECTURE.md} §Why a capability, again): no resume token,
 * no {@code GOAWAY}, and {@code ERROR SERVER_SHUTTING_DOWN} at the stop. Its name is still held for it across the
 * restart. The servers run beside this class's own context, so the cached test context is never closed.
 */
@TestPropertySource(properties = "jburrow.tcp.port-range=32020-32029")
class Phase3ClientIntegrationTest extends TunnelTestSupport {

    private static final String OWN_PORTS = "--jburrow.tcp.port-range=32030-32039";
    private static final List<String> PHASE_3_CAPABILITIES = List.of(Capability.HTTP.wireName());

    @Autowired
    private JdbcConnectionDetails db;

    @Autowired
    private Environment environment;

    @Test
    void aClientWithoutResumeIsEndedAtTheStopAsBeforeAndItsNameIsHeldForIt() throws Exception {
        ConfigurableApplicationContext first = closeAfterTest(startServer());
        URI firstServer = connectUriOf(first);
        Tunnel cli = openTunnel(
                httpClient(ALICE_TOKEN, unusedPort()).server(firstServer).capabilities(Set.of(Capability.HTTP)));
        ScriptedPeer scripted = helloed(firstServer);
        scripted.send(new Frame.Register(new Register(BOB_TOKEN, List.of(http(null)))));
        Registered reply = scripted.expect(Frame.Registered.class, TIMEOUT).message();
        assertThat(reply.resumeToken()).isNull();
        String name = cli.host().substring(0, cli.host().indexOf('.'));

        first.close();

        scripted.expectError(ErrorCode.SERVER_SHUTTING_DOWN, TIMEOUT);
        TunnelClosed closed = cli.awaitClosed(TIMEOUT);
        assertThat(closed.reason()).isEqualTo(TunnelClosed.Reason.SERVER_ERROR);
        assertThat(closed.code()).contains(ErrorCode.SERVER_SHUTTING_DOWN);

        ConfigurableApplicationContext second = closeAfterTest(startServer());
        URI secondServer = connectUriOf(second);
        ScriptedPeer bob = helloed(secondServer);
        bob.send(new Frame.Register(new Register(BOB_TOKEN, List.of(http(name)))));
        assertThat(bob.expect(Frame.Error.class, TIMEOUT).body().code()).isEqualTo(ErrorCode.SUBDOMAIN_UNAVAILABLE);
        ScriptedPeer alice = helloed(secondServer);
        alice.send(new Frame.Register(new Register(ALICE_TOKEN, List.of(http(name)))));
        assertThat(alice.expect(Frame.Registered.class, TIMEOUT)
                        .message()
                        .tunnels()
                        .getFirst()
                        .publicHost())
                .isEqualTo(cli.host());
    }

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
                        OWN_PORTS);
    }

    private static URI connectUriOf(ConfigurableApplicationContext context) {
        return URI.create(
                "ws://localhost:" + context.getBean(EdgeServer.class).boundPort() + ScriptedPeer.CONNECT_PATH);
    }

    /** A scripted phase 3 client: it offers {@code http} only, and its session stays open until the test ends. */
    private ScriptedPeer helloed(URI server) {
        ScriptedPeer peer = closeAfterTest(ScriptedPeer.connect(server, ProtocolVersion.CURRENT));
        peer.send(new Frame.Hello(new Hello(
                new ClientInfo("jburrow-e2e", "0.3.0", "linux", "amd64"),
                PHASE_3_CAPABILITIES,
                ScriptedPeer.CLIENT_WINDOWS)));
        peer.expect(Frame.ServerHello.class, TIMEOUT);
        assertThat(peer.resumeNegotiated()).isFalse();
        return peer;
    }

    private static TunnelRequest http(String subdomain) {
        return new TunnelRequest("web", TunnelKind.HTTP, subdomain, null);
    }
}
