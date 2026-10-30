package io.jburrow.client.tunnel.adapter.netty;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import io.jburrow.client.ClientSettings;
import io.jburrow.client.TunnelHandle;
import io.jburrow.client.tunnel.domain.LocalTarget;
import io.jburrow.client.tunnel.domain.TunnelSpec;
import io.jburrow.protocol.adapter.netty.Frame;
import io.jburrow.protocol.control.BodyFraming;
import io.jburrow.protocol.control.ClientInfo;
import io.jburrow.protocol.control.HeaderFields;
import io.jburrow.protocol.control.HttpRequestHead;
import io.jburrow.protocol.control.HttpResponseHead;
import io.jburrow.protocol.control.Open;
import io.jburrow.protocol.control.Registered;
import io.jburrow.protocol.control.RegisteredTunnel;
import io.jburrow.protocol.control.RemoteAddress;
import io.jburrow.protocol.control.ServerHello;
import io.jburrow.protocol.control.ServerInfo;
import io.jburrow.protocol.control.SessionLimits;
import io.jburrow.protocol.control.TunnelKind;
import io.jburrow.protocol.control.TunnelRequest;
import io.jburrow.protocol.control.Windows;
import io.jburrow.protocol.testsupport.ScriptedPeer;
import io.jburrow.protocol.wire.ErrorCode;
import io.jburrow.protocol.wire.FrameLimits;
import io.jburrow.protocol.wire.ProtocolVersion;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * The real client with one HTTP tunnel, established against {@link ScriptedPeer} playing the server, which polices the
 * windows it announces and every HTTP stream rule. Streams are opened and fed from the test; the frames that come back
 * are sorted by stream, so several exchanges can run at once.
 */
final class HttpTunnelFixture implements AutoCloseable {

    static final Duration TIMEOUT = Duration.ofSeconds(30);
    static final UUID TUNNEL_ID = UUID.fromString("5d0f4f0e-31a6-4a39-8d51-0c0f7f1f6c23");

    private static final UUID SESSION_ID = UUID.fromString("0b8e5c1e-7c55-4c46-9a3e-2f1d0c9e4b7a");
    private static final RemoteAddress VISITOR = new RemoteAddress("203.0.113.9", 51_234);
    private static final SessionLimits LIMITS =
            new SessionLimits(FrameLimits.MAX_DATA_PAYLOAD, 1, 1_000, 15_000, 45_000);
    /** Keeps the start of each body for assertions; the rest only counts towards the checksum. */
    private static final int KEPT_BODY = 1024 * 1024;

    private final ExecutorService threads = Executors.newVirtualThreadPerTaskExecutor();
    private final Map<Integer, Response> responses = new HashMap<>();
    private final RecordingEvents events = new RecordingEvents();
    private final ScriptedPeer peer;
    private final NettyTunnelClient client;
    private final TunnelHandle handle;

    private HttpTunnelFixture(LocalTarget target, Windows serverWindows) throws Exception {
        peer = ScriptedPeer.listen(0, ProtocolVersion.CURRENT);
        client = new NettyTunnelClient(
                new ClientSettings(
                        peer.uri(),
                        "jb_" + "t".repeat(43),
                        List.of(TunnelSpec.http("default", target)),
                        events,
                        new ClientInfo("jburrow-test", "1.0.0", "linux", "amd64"),
                        Optional.empty(),
                        TIMEOUT,
                        ClientSettings.DEFAULT_WINDOWS),
                HostResolver.SYSTEM);
        Future<TunnelHandle> start = threads.submit(client::start);
        assertThat(peer.expect(Frame.Hello.class, TIMEOUT).message().capabilities())
                .containsExactly("http");
        peer.send(new Frame.ServerHello(new ServerHello(
                SESSION_ID, new ServerInfo("jburrow-server", "0.3.0"), List.of("http"), LIMITS, serverWindows)));
        assertThat(peer.expect(Frame.Register.class, TIMEOUT).message().tunnels())
                .containsExactly(new TunnelRequest("default", TunnelKind.HTTP, null, null));
        peer.send(new Frame.Registered(new Registered(
                List.of(RegisteredTunnel.http("default", TUNNEL_ID, "http", "quiet-otter-4f2a.localhost", 7080)))));
        handle = start.get(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        peer.ignoreWindowUpdates();
    }

    /** Established with the reference server's windows, returning credit as a correct receiver does. */
    static HttpTunnelFixture start(LocalTarget target) throws Exception {
        HttpTunnelFixture fixture = new HttpTunnelFixture(target, ScriptedPeer.SERVER_WINDOWS);
        fixture.peer.autoGrant();
        return fixture;
    }

    /** Established with the server announcing {@code windows}, and returning no credit until the test does. */
    static HttpTunnelFixture withWindows(LocalTarget target, Windows windows) throws Exception {
        return new HttpTunnelFixture(target, windows);
    }

    ScriptedPeer peer() {
        return peer;
    }

    TunnelHandle handle() {
        return handle;
    }

    RecordingEvents events() {
        return events;
    }

    ExecutorService threads() {
        return threads;
    }

    void open(int streamId, HttpRequestHead request) {
        peer.send(new Frame.Open(streamId, new Open(TUNNEL_ID, TunnelKind.HTTP, VISITOR, request)));
    }

    /** Sends {@code bytes} of request body in full frames, each once the client has given credit for it. */
    void sendBody(int streamId, byte[] bytes) {
        for (int offset = 0; offset < bytes.length; offset += FrameLimits.MAX_DATA_PAYLOAD) {
            int length = Math.min(FrameLimits.MAX_DATA_PAYLOAD, bytes.length - offset);
            peer.sendData(streamId, Unpooled.copiedBuffer(bytes, offset, length), TIMEOUT);
        }
    }

    /** Sends {@link LocalHttpService#randomBody} as the request body, as credit allows. */
    void sendRandomBody(int streamId, long length, long seed) throws java.io.IOException {
        LocalHttpService.randomBody(
                length,
                seed,
                (bytes, count) -> peer.sendData(streamId, Unpooled.copiedBuffer(bytes, 0, count), TIMEOUT));
    }

    void endRequest(int streamId) {
        peer.send(Frame.Close.half(streamId));
    }

    /** The response on {@code streamId}, once it has ended with {@code CLOSE} or a reset. */
    Response response(int streamId) {
        while (!responses.containsKey(streamId) || !responses.get(streamId).ended()) {
            take();
        }
        return responses.get(streamId);
    }

    /** Waits for {@code streamId}'s reset after its response, which ends what is left of its request. */
    ErrorCode resetAfterResponse(int streamId) {
        Response response = response(streamId);
        while (response.reset == null) {
            take();
        }
        return response.reset;
    }

    /** Takes frames until {@code streamId} has received at least {@code bytes} of response body; returns how much. */
    long awaitBody(int streamId, long bytes) {
        while (stream(streamId).bodyBytes < bytes) {
            take();
        }
        return stream(streamId).bodyBytes;
    }

    /** Nothing arrives from the client for {@code quiet}: no frame past the credit, which the peer would also report. */
    void assertNothingFor(Duration quiet) {
        assertThatExceptionOfType(AssertionError.class)
                .isThrownBy(() -> peer.expect(Frame.class, quiet))
                .withMessageContaining("got nothing");
    }

    @Override
    public void close() {
        client.close();
        peer.close();
        threads.shutdownNow();
    }

    private void take() {
        Frame frame = peer.expect(Frame.class, TIMEOUT);
        try {
            switch (frame) {
                case Frame.Headers headers -> stream(headers.streamId()).heads.add(headers.head());
                case Frame.Data data -> stream(data.streamId()).take(data.payload());
                case Frame.Close close -> {
                    Response response = stream(close.streamId());
                    if (close.reset().isPresent()) {
                        response.reset = close.reset().get();
                    } else {
                        response.closed = true;
                    }
                }
                default -> throw new AssertionError("Unexpected frame from the client: " + frame);
            }
        } finally {
            frame.release();
        }
    }

    private Response stream(int streamId) {
        return responses.computeIfAbsent(streamId, id -> new Response());
    }

    static HttpRequestHead request(String method, String target, BodyFraming body, String... namesAndValues) {
        String[] fields = new String[namesAndValues.length + 2];
        fields[0] = "host";
        fields[1] = "quiet-otter-4f2a.localhost";
        System.arraycopy(namesAndValues, 0, fields, 2, namesAndValues.length);
        return new HttpRequestHead(method, target, HeaderFields.of(fields), body);
    }

    /** What came back on one stream. */
    static final class Response {

        private final List<HttpResponseHead> heads = new ArrayList<>();
        private final ByteArrayOutputStream kept = new ByteArrayOutputStream();
        private final MessageDigest digest = sha256();
        private final List<Integer> frames = new ArrayList<>();
        private long bodyBytes;
        private boolean closed;
        private ErrorCode reset;

        boolean ended() {
            return closed || reset != null;
        }

        /** Every head, informational ones first. */
        List<HttpResponseHead> heads() {
            return heads;
        }

        /** The final head. */
        HttpResponseHead head() {
            return heads.getLast();
        }

        int status() {
            return head().status();
        }

        String body() {
            return kept.toString(StandardCharsets.ISO_8859_1);
        }

        long bodyBytes() {
            return bodyBytes;
        }

        String sha256Hex() {
            try {
                return HexFormat.of().formatHex(((MessageDigest) digest.clone()).digest());
            } catch (CloneNotSupportedException e) {
                throw new IllegalStateException(e);
            }
        }

        /** The payload length of each {@code DATA}, in order. */
        List<Integer> frames() {
            return frames;
        }

        boolean closed() {
            return closed;
        }

        Optional<ErrorCode> reset() {
            return Optional.ofNullable(reset);
        }

        private void take(ByteBuf payload) {
            int length = payload.readableBytes();
            byte[] bytes = new byte[length];
            payload.getBytes(payload.readerIndex(), bytes);
            digest.update(bytes);
            if (kept.size() < KEPT_BODY) {
                kept.write(bytes, 0, Math.min(length, KEPT_BODY - kept.size()));
            }
            frames.add(length);
            bodyBytes += length;
        }

        private static MessageDigest sha256() {
            try {
                return MessageDigest.getInstance("SHA-256");
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException(e);
            }
        }
    }
}
