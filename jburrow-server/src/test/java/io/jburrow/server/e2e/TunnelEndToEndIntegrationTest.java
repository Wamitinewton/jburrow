package io.jburrow.server.e2e;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.jburrow.client.TunnelStartException;
import io.jburrow.protocol.control.TunnelKind;
import io.jburrow.protocol.wire.ErrorCode;
import io.jburrow.server.e2e.RecordingEvents.StreamClosed;
import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.net.SocketException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/** Real bytes through real tunnels, in every TCP shape phase 1 supports. */
@TestPropertySource(properties = {"jburrow.tcp.port-range=41000-41099", "jburrow.edge.max-sessions-per-principal=2"})
class TunnelEndToEndIntegrationTest extends TunnelTestSupport {

    private static final long LARGE = 64L * 1024 * 1024;

    @Test
    void smallMessagesEchoBackInOrder() throws Exception {
        LocalService echo = closeAfterTest(LocalService.echo());
        Tunnel tunnel = openTunnel(echo);

        assertThat(tunnel.registered().kind()).isEqualTo(TunnelKind.TCP);
        assertThat(tunnel.registered().url()).isEqualTo("tcp://localhost:" + tunnel.port());
        assertThat(tunnel.port()).isBetween(41000, 41099);

        Socket visitor = visit(tunnel.port());
        for (int i = 0; i < 10; i++) {
            byte[] message = ("message " + i + "\n").getBytes(US_ASCII);
            assertThat(roundTrip(visitor, message)).isEqualTo(message);
        }
    }

    @Test
    void sixtyFourMebibytesUploadIntact() throws Exception {
        CompletableFuture<Received> received = new CompletableFuture<>();
        LocalService sink = closeAfterTest(LocalService.start(connection -> {
            received.complete(drain(connection.getInputStream()));
        }));
        Tunnel tunnel = openTunnel(sink);
        Socket visitor = visit(tunnel.port());

        String sent = send(visitor.getOutputStream(), LARGE, 1);
        visitor.shutdownOutput();

        assertThat(received.get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)).isEqualTo(new Received(LARGE, sent));
        assertThat(visitor.getInputStream().read())
                .as("the stream ends once the sink closes")
                .isEqualTo(-1);
        StreamClosed closed = tunnel.events().awaitStreamClosed(TIMEOUT);
        assertThat(closed.bytesIn()).isEqualTo(LARGE);
        assertThat(closed.reset()).isEmpty();
    }

    @Test
    void sixtyFourMebibytesDownloadIntact() throws Exception {
        LocalService source = closeAfterTest(LocalService.start(connection -> {
            send(connection.getOutputStream(), LARGE, 2);
            connection.shutdownOutput();
            drain(connection.getInputStream());
        }));
        Tunnel tunnel = openTunnel(source);
        Socket visitor = visit(tunnel.port());

        Received received = drain(visitor.getInputStream());
        visitor.shutdownOutput();

        assertThat(received).isEqualTo(new Received(LARGE, expectedDigest(LARGE, 2)));
        assertThat(tunnel.events().awaitStreamClosed(TIMEOUT).bytesOut()).isEqualTo(LARGE);
    }

    @Test
    void aVisitorThatHalfClosesStillReceivesTheReply() throws Exception {
        byte[] reply = "the reply, sent after the request ended".getBytes(US_ASCII);
        LocalService replyAfterEof = closeAfterTest(LocalService.start(connection -> {
            InputStream in = connection.getInputStream();
            long requestLength = drain(in).length();
            connection.getOutputStream().write(("request " + requestLength + ": ").getBytes(US_ASCII));
            connection.getOutputStream().write(reply);
            connection.shutdownOutput();
        }));
        Tunnel tunnel = openTunnel(replyAfterEof);
        Socket visitor = visit(tunnel.port());

        visitor.getOutputStream().write(new byte[1000]);
        visitor.shutdownOutput();

        assertThat(new String(visitor.getInputStream().readAllBytes(), US_ASCII))
                .isEqualTo("request 1000: " + new String(reply, US_ASCII));
        assertThat(tunnel.events().awaitStreamClosed(TIMEOUT).reset()).isEmpty();
    }

    @Test
    void aLocalServiceThatResetsResetsTheVisitor() throws Exception {
        LocalService resetting = closeAfterTest(LocalService.start(connection -> {
            connection.getInputStream().readNBytes(5);
            connection.setSoLinger(true, 0);
            connection.close();
        }));
        Tunnel tunnel = openTunnel(resetting);
        Socket visitor = visit(tunnel.port());

        visitor.getOutputStream().write("hello".getBytes(US_ASCII));

        assertThatThrownBy(() -> visitor.getInputStream().read())
                .isInstanceOf(SocketException.class)
                .hasMessageContaining("reset");
        assertThat(tunnel.events().awaitStreamClosed(TIMEOUT).reset()).contains(ErrorCode.PEER_RESET);
        assertOpen(tunnel.handle());
    }

    @Test
    void aLocalServiceThatIsDownClosesTheVisitorAndTheSessionStaysUp() throws Exception {
        int localPort = unusedPort();
        Tunnel tunnel = openTunnel(ALICE_TOKEN, tcp(localPort));

        Socket first = visit(tunnel.port());
        assertThat(endOf(first)).as("the visitor's connection closes").isTrue();
        assertThat(tunnel.events().awaitStreamClosed(TIMEOUT).reset()).contains(ErrorCode.LOCAL_SERVICE_UNREACHABLE);
        assertOpen(tunnel.handle());

        closeAfterTest(LocalService.start(
                localPort, connection -> LocalService.copy(connection.getInputStream(), connection.getOutputStream())));
        awaitNoStreams();
        Socket second = visit(tunnel.port());
        assertThat(roundTrip(second, "up now".getBytes(US_ASCII))).isEqualTo("up now".getBytes(US_ASCII));
    }

    @Test
    void aSecondVisitorIsRefusedWhileTheFirstIsConnected() throws Exception {
        LocalService echo = closeAfterTest(LocalService.echo());
        Tunnel tunnel = openTunnel(echo);
        byte[] ping = "ping".getBytes(US_ASCII);

        Socket first = visit(tunnel.port());
        assertThat(roundTrip(first, ping)).isEqualTo(ping);

        Socket second = visit(tunnel.port());
        assertThat(endOf(second)).as("the second visitor is closed at once").isTrue();
        assertThat(roundTrip(first, ping)).as("the first is unaffected").isEqualTo(ping);
        assertThat(echo.accepted()).isOne();

        first.close();
        tunnel.events().awaitStreamClosed(TIMEOUT);
        awaitNoStreams();

        Socket third = visit(tunnel.port());
        assertThat(roundTrip(third, ping)).isEqualTo(ping);
    }

    @Test
    void aBadTokenFailsTheStart() {
        assertThatThrownBy(() -> openTunnel(UNKNOWN_TOKEN, tcp(unusedPort())))
                .isInstanceOfSatisfying(TunnelStartException.class, failure -> {
                    assertThat(failure.kind()).isEqualTo(TunnelStartException.Kind.SERVER_ERROR);
                    assertThat(failure.code()).contains(ErrorCode.AUTHTOKEN_INVALID);
                });
    }

    @Test
    void aRequestedPortIsGrantedAgainAfterItsTunnelCloses() throws Exception {
        LocalService echo = closeAfterTest(LocalService.echo());
        int requested = 41042;

        Tunnel first = openTunnel(ALICE_TOKEN, tcp(echo.port()).remotePort(requested));
        assertThat(first.port()).isEqualTo(requested);
        first.handle().close();
        first.awaitClosed(TIMEOUT);
        awaitNoReservedPorts();

        Tunnel second = openTunnel(ALICE_TOKEN, tcp(echo.port()).remotePort(requested));
        assertThat(second.port()).isEqualTo(requested);
        byte[] ping = "same port".getBytes(US_ASCII);
        assertThat(roundTrip(visit(requested), ping)).isEqualTo(ping);
    }

    @Test
    void principalsHaveTunnelsAtOnceUpToTheirSessionLimit() throws Exception {
        LocalService echo = closeAfterTest(LocalService.echo());
        Tunnel alice = openTunnel(ALICE_TOKEN, tcp(echo.port()));
        Tunnel bob = openTunnel(BOB_TOKEN, tcp(echo.port()));
        Tunnel aliceAgain = openTunnel(ALICE_TOKEN, tcp(echo.port()));

        assertThat(List.of(alice.port(), bob.port(), aliceAgain.port())).doesNotHaveDuplicates();
        for (Tunnel tunnel : List.of(alice, bob, aliceAgain)) {
            byte[] message = ("through " + tunnel.port()).getBytes(US_ASCII);
            assertThat(roundTrip(visit(tunnel.port()), message)).isEqualTo(message);
        }

        assertThatThrownBy(() -> openTunnel(ALICE_TOKEN, tcp(echo.port())))
                .isInstanceOfSatisfying(
                        TunnelStartException.class,
                        failure -> assertThat(failure.code()).contains(ErrorCode.SESSION_LIMIT_EXCEEDED));
        assertOpen(alice.handle());
        assertOpen(aliceAgain.handle());
    }

    /** Whether the connection ends, by a close or a reset, before {@link #TIMEOUT}. */
    private static boolean endOf(Socket visitor) throws IOException {
        try {
            return visitor.getInputStream().read() == -1;
        } catch (SocketException reset) {
            return true;
        }
    }
}
