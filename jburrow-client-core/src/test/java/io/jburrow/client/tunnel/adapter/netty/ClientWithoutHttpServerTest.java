package io.jburrow.client.tunnel.adapter.netty;

import static org.assertj.core.api.Assertions.assertThat;

import io.jburrow.client.ClientSettings;
import io.jburrow.client.TunnelHandle;
import io.jburrow.client.TunnelStartException;
import io.jburrow.client.tunnel.domain.LocalTarget;
import io.jburrow.client.tunnel.domain.TunnelSpec;
import io.jburrow.protocol.adapter.netty.Frame;
import io.jburrow.protocol.control.ClientInfo;
import io.jburrow.protocol.control.ServerHello;
import io.jburrow.protocol.control.ServerInfo;
import io.jburrow.protocol.control.SessionLimits;
import io.jburrow.protocol.testsupport.ScriptedPeer;
import io.jburrow.protocol.wire.ProtocolVersion;
import io.netty.handler.codec.http.websocketx.WebSocketCloseStatus;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * An HTTP tunnel asked of a server that doesn't answer {@code http}, such as a phase 2 server: the client learns it from
 * the server's {@code HELLO}, before it registers, and ends the session on purpose (§4.2, §8.3).
 */
class ClientWithoutHttpServerTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private final ExecutorService threads = Executors.newVirtualThreadPerTaskExecutor();
    private final RecordingEvents events = new RecordingEvents();
    private ScriptedPeer peer;
    private NettyTunnelClient client;

    @AfterEach
    void stop() {
        if (client != null) {
            client.close();
        }
        if (peer != null) {
            peer.close();
        }
        threads.shutdownNow();
    }

    @Test
    void startFailsWithTheReasonAndNothingIsRegistered() throws Exception {
        peer = ScriptedPeer.listen(0, ProtocolVersion.CURRENT);
        client = new NettyTunnelClient(
                new ClientSettings(
                        peer.uri(),
                        "jb_" + "t".repeat(43),
                        List.of(TunnelSpec.http("default", LocalTarget.of("localhost", 3000))),
                        events,
                        new ClientInfo("jburrow-test", "1.0.0", null, null),
                        Optional.empty(),
                        TIMEOUT,
                        ClientSettings.DEFAULT_WINDOWS),
                HostResolver.SYSTEM);
        Future<TunnelHandle> start = threads.submit(client::start);

        assertThat(peer.expect(Frame.Hello.class, TIMEOUT).message().capabilities())
                .containsExactly("http");
        peer.send(new Frame.ServerHello(new ServerHello(
                UUID.randomUUID(),
                new ServerInfo("jburrow-server", "0.2.0"),
                List.of(),
                new SessionLimits(65_536, 1, 1_000, 15_000, 45_000),
                ScriptedPeer.SERVER_WINDOWS)));

        assertThat(peer.expectClosed(TIMEOUT)).isEqualTo(WebSocketCloseStatus.NORMAL_CLOSURE);
        TunnelStartException failure = failure(start);
        assertThat(failure.kind()).isEqualTo(TunnelStartException.Kind.UNSUPPORTED_BY_SERVER);
        assertThat(failure.code()).isEmpty();
        assertThat(failure).hasMessage("This server doesn't support HTTP tunnels.");
        assertThat(events.events()).isEmpty();
    }

    private static TunnelStartException failure(Future<TunnelHandle> start) throws Exception {
        try {
            start.get(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof TunnelStartException failure) {
                return failure;
            }
            throw e;
        }
        throw new AssertionError("start() succeeded");
    }
}
