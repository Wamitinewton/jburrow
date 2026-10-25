package io.jburrow.server.e2e;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.jburrow.client.tunnel.domain.TunnelClosed;
import io.jburrow.protocol.testsupport.ScriptedPeer;
import io.jburrow.protocol.wire.ErrorCode;
import io.jburrow.server.JBurrowServerApplication;
import io.jburrow.server.core.tunnel.adapter.netty.edge.EdgeServer;
import java.net.Socket;
import java.net.SocketException;
import java.net.URI;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.test.context.TestPropertySource;

/**
 * Closing the application ends every session the way {@code docs/phase-1-tcp-tunnel/ARCHITECTURE.md} §Lifecycle and
 * shutdown says. The test runs and closes a second application against this class's Postgres and Mailpit, so the
 * cached test context is never closed under the context cache.
 */
@TestPropertySource(properties = "jburrow.tcp.port-range=41300-41349")
class ShutdownIntegrationTest extends TunnelTestSupport {

    private static final Duration CONTEXT_CLOSE_LIMIT = Duration.ofSeconds(10);

    @Test
    void closingTheApplicationEndsEverySessionAndFreesEveryPort(
            @Autowired JdbcConnectionDetails db, @Autowired Environment environment) throws Exception {
        ConfigurableApplicationContext context =
                closeAfterTest(new SpringApplicationBuilder(JBurrowServerApplication.class)
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
                                "--jburrow.tcp.port-range=41350-41399"));
        URI server = URI.create(
                "ws://localhost:" + context.getBean(EdgeServer.class).boundPort() + ScriptedPeer.CONNECT_PATH);
        LocalService echo = closeAfterTest(LocalService.echo());
        Tunnel alice = openTunnel(client(ALICE_TOKEN, tcp(echo.port())).server(server));
        Tunnel bob = openTunnel(client(BOB_TOKEN, tcp(echo.port())).server(server));
        Socket visitor = visit(alice.port());
        byte[] message = "before shutdown".getBytes(US_ASCII);
        assertThat(roundTrip(visitor, message)).isEqualTo(message);

        long closingFrom = System.nanoTime();
        context.close();
        assertThat(Duration.ofNanos(System.nanoTime() - closingFrom)).isLessThan(CONTEXT_CLOSE_LIMIT);

        for (Tunnel tunnel : new Tunnel[] {alice, bob}) {
            TunnelClosed closed = tunnel.awaitClosed(TIMEOUT);
            assertThat(closed.reason()).isEqualTo(TunnelClosed.Reason.SERVER_ERROR);
            assertThat(closed.code()).contains(ErrorCode.SERVER_SHUTTING_DOWN);
        }
        assertThatThrownBy(() -> visitor.getInputStream().read())
                .as("the visitor's connection is reset")
                .isInstanceOf(SocketException.class)
                .hasMessageContaining("reset");
        assertThat(bindable(alice.port())).isTrue();
        assertThat(bindable(bob.port())).isTrue();
    }
}
