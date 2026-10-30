package io.jburrow.server.core.tunnel.adapter.netty.http;

import static io.jburrow.server.core.tunnel.adapter.netty.http.HttpHarness.TIMEOUT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import io.jburrow.protocol.adapter.netty.Frame;
import io.jburrow.protocol.control.BodyFraming;
import io.jburrow.protocol.control.HeaderFields;
import io.jburrow.protocol.control.HttpResponseHead;
import io.jburrow.protocol.control.RegisteredTunnel;
import io.jburrow.protocol.control.Windows;
import io.jburrow.protocol.flow.FlowLimits;
import io.jburrow.protocol.testsupport.ScriptedPeer;
import io.jburrow.server.core.tunnel.adapter.netty.control.SessionContext;
import io.jburrow.server.core.tunnel.adapter.netty.edge.EdgeTransport;
import io.jburrow.server.core.tunnel.adapter.netty.support.Transports;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * {@code docs/phase-3-http-tunnels/ARCHITECTURE.md} §Visitor to client: demand reads, over real sockets: request bodies
 * reach a client with the smallest windows the protocol allows, never past them, in reads no larger than one
 * reservation, and every reservation is spent or given back.
 */
class DemandReadIntegrationTest {

    private static final Windows TINY = new Windows(FlowLimits.MIN_STREAM_WINDOW, FlowLimits.MIN_STREAM_WINDOW);
    private static final int CHUNK = 7_919;

    private HttpHarness harness;

    @AfterEach
    void stop() throws Exception {
        if (harness != null) {
            harness.close();
        }
    }

    static Stream<Arguments> uploads() {
        List<Arguments> cases = new ArrayList<>();
        for (EdgeTransport transport : Transports.available()) {
            for (int size : new int[] {0, 1, ExchangeGate.DEMAND, 10 * 1024 * 1024}) {
                cases.add(arguments(transport, size, false));
                cases.add(arguments(transport, size, true));
            }
        }
        return cases.stream();
    }

    @ParameterizedTest(name = "{0}, {1} bytes, chunked {2}")
    @MethodSource("uploads")
    void anUploadReachesTheClientWithinItsWindowsInReadsOfOneReservation(
            EdgeTransport transport, int size, boolean chunked) throws Exception {
        harness = new HttpHarness(HttpHarness.Settings.defaults(transport));
        ScriptedPeer peer = harness.connect();
        peer.ignoreWindowUpdates();
        RegisteredTunnel tunnel = harness.establish(peer, "uploads", TINY);
        peer.autoGrant();
        SessionContext session = harness.onlySession();
        Visitor visitor = harness.visitorOnAnotherLoop(tunnel.publicHost(), session);
        byte[] body = pattern(size);

        CompletableFuture<Void> sent;
        try (var uploader = Executors.newVirtualThreadPerTaskExecutor()) {
            sent = CompletableFuture.runAsync(() -> upload(visitor, body, chunked), uploader);
            Frame.Open open = peer.expect(Frame.Open.class, TIMEOUT);
            BodyFraming expected = chunked ? BodyFraming.CHUNKED : size == 0 ? BodyFraming.EMPTY : BodyFraming.LENGTH;
            assertThat(open.message().request().body()).isEqualTo(expected);

            Received received = receive(peer, open.streamId());
            sent.join();

            assertThat(received.bytes()).isEqualTo(size);
            assertThat(received.digest()).isEqualTo(digest(body));
            assertThat(received.largestData()).isLessThanOrEqualTo(ExchangeGate.DEMAND);

            peer.send(new Frame.Headers(
                    open.streamId(),
                    new HttpResponseHead(200, HeaderFields.of("content-length", "0"), BodyFraming.EMPTY)));
            peer.send(Frame.Close.half(open.streamId()));
            assertThat(visitor.response().status()).isEqualTo(200);
        }
        HttpHarness.awaitTrue(() -> harness.streamQuota.inUse() == 0);
        assertThat(HttpHarness.onLoop(session, () -> session.session().reserved()))
                .isZero();
    }

    private static void upload(Visitor visitor, byte[] body, boolean chunked) {
        try {
            if (!chunked) {
                visitor.request("POST", "/upload", body);
                return;
            }
            visitor.request("POST", "/upload", "Transfer-Encoding", "chunked");
            for (int from = 0; from < body.length; from += CHUNK) {
                int to = Math.min(body.length, from + CHUNK);
                visitor.chunk(Arrays.copyOfRange(body, from, to));
            }
            visitor.chunk(new byte[0]);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private record Received(long bytes, byte[] digest, int largestData) {}

    /** The request body on {@code streamId}, up to the server's {@code CLOSE}, digested as it arrives. */
    private static Received receive(ScriptedPeer peer, int streamId) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        long bytes = 0;
        int largest = 0;
        while (true) {
            Frame frame = peer.expect(Frame.class, TIMEOUT);
            assertThat(frame.streamId()).isEqualTo(streamId);
            if (!(frame instanceof Frame.Data data)) {
                assertThat(frame)
                        .isInstanceOfSatisfying(
                                Frame.Close.class,
                                close -> assertThat(close.reset()).isEmpty());
                return new Received(bytes, digest.digest(), largest);
            }
            int length = data.payload().readableBytes();
            byte[] payload = new byte[length];
            data.payload().readBytes(payload);
            data.release();
            digest.update(payload);
            bytes += length;
            largest = Math.max(largest, length);
        }
    }

    private static byte[] digest(byte[] body) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(body);
    }

    private static byte[] pattern(int length) {
        byte[] bytes = new byte[length];
        for (int i = 0; i < length; i++) {
            bytes[i] = (byte) (i * 131 + (i >>> 9));
        }
        return bytes;
    }
}
