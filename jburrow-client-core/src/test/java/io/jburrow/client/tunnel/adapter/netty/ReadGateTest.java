package io.jburrow.client.tunnel.adapter.netty;

import static org.assertj.core.api.Assertions.assertThat;

import io.jburrow.client.ClientSettings;
import io.jburrow.client.tunnel.application.port.TunnelEvents;
import io.jburrow.client.tunnel.domain.ClientSession;
import io.jburrow.client.tunnel.domain.LocalTarget;
import io.jburrow.client.tunnel.domain.TunnelSpec;
import io.jburrow.protocol.adapter.netty.Frame;
import io.jburrow.protocol.control.ClientInfo;
import io.jburrow.protocol.control.Open;
import io.jburrow.protocol.control.Registered;
import io.jburrow.protocol.control.RegisteredTunnel;
import io.jburrow.protocol.control.RemoteAddress;
import io.jburrow.protocol.control.ServerHello;
import io.jburrow.protocol.control.ServerInfo;
import io.jburrow.protocol.control.SessionLimits;
import io.jburrow.protocol.control.TunnelKind;
import io.jburrow.protocol.control.Windows;
import io.jburrow.protocol.testsupport.DuplexEmbeddedChannel;
import io.jburrow.protocol.wire.ErrorCode;
import io.jburrow.protocol.wire.StreamIds;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.RecvByteBufAllocator;
import io.netty.channel.WriteBufferWaterMark;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.Promise;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Queue;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@link ReadGate}'s rules one by one, {@code docs/phase-2-multiplexing/ARCHITECTURE.md} §The client, through the
 * connector that owns it, with no sockets: the server's {@code ReadGateTest} with "visitor" read as "local connection".
 * The server's windows are the smallest allowed, 64 KiB for a stream and for the session, so one full read spends
 * either.
 */
class ReadGateTest {

    private static final int WINDOW = 65_536;
    private static final UUID SESSION_ID = UUID.fromString("0b8e5c1e-7c55-4c46-9a3e-2f1d0c9e4b7a");
    private static final UUID TUNNEL_ID = UUID.fromString("5d0f4f0e-31a6-4a39-8d51-0c0f7f1f6c23");
    private static final InetSocketAddress LOCAL = new InetSocketAddress("127.0.0.1", 22);
    private static final WriteBufferWaterMark ROOMY = new WriteBufferWaterMark(1024 * 1024, 2 * 1024 * 1024);

    private final List<EmbeddedChannel> locals = new ArrayList<>();
    /** What each {@code OPEN}'s dial returns, in order: made at once, or still connecting. */
    private final Queue<Future<Channel>> dials = new ArrayDeque<>();

    private EmbeddedChannel control;
    private ClientSession session;
    private LocalConnector connector;
    private int nextStreamId = 1;

    @BeforeEach
    void setUp() {
        control = new EmbeddedChannel();
        control.config().setWriteBufferWaterMark(ROOMY);
        establish(new SessionLimits(65_536, 1, 1, 15_000, 45_000));
    }

    private void establish(SessionLimits limits) {
        ControlConnection connection = new ControlConnection(new ClientSettings(
                URI.create("ws://localhost:7080/v1/connect"),
                "jb_" + "t".repeat(43),
                List.of(TunnelSpec.tcp("default", LocalTarget.of("127.0.0.1", 22))),
                new TunnelEvents() {},
                new ClientInfo("jburrow-test", "1.0.0", null, null),
                Optional.empty(),
                ClientSettings.DEFAULT_HANDSHAKE_TIMEOUT,
                ClientSettings.DEFAULT_WINDOWS));
        session = connection.session();
        session.onUpgraded();
        session.onServerHello(new ServerHello(
                SESSION_ID, new ServerInfo("jburrow-server", "0.3.0"), List.of(), limits, new Windows(WINDOW, WINDOW)));
        session.onRegistered(new Registered(
                List.of(RegisteredTunnel.of("default", TUNNEL_ID, TunnelKind.TCP, "tcp.example.dev", 20_417))));
        connector = new LocalConnector(connection, control, this::dial, () -> 0L);
    }

    @AfterEach
    void tearDown() {
        locals.forEach(EmbeddedChannel::finishAndReleaseAll);
        control.finishAndReleaseAll();
    }

    @Test
    void eachConnectionsReadsAreSizedByWhatItsStreamMaySendNow() {
        DuplexEmbeddedChannel local = connect();
        RecvByteBufAllocator.Handle reads =
                local.config().getRecvByteBufAllocator().newHandle();
        reads.reset(local.config());
        assertThat(reads.guess()).isEqualTo(2_048);

        read(local, WINDOW - 100);

        assertThat(gate().sendable(handler(local))).isEqualTo(100);
        assertThat(reads.guess()).isEqualTo(100);
    }

    @Test
    void noReadIsLongerThanTheServersLargestPayload() {
        establish(new SessionLimits(1_000, 1, 1, 15_000, 45_000));
        DuplexEmbeddedChannel local = connect();

        assertThat(gate().sendable(handler(local))).isEqualTo(1_000);
    }

    @Test
    void aConnectionWhoseReadSpendsItsStreamCreditIsPausedAtOnce() {
        DuplexEmbeddedChannel local = connect();

        read(local, WINDOW);

        assertThat(dataSent()).containsExactly(WINDOW);
        assertThat(local.config().isAutoRead()).isFalse();
        assertThat(handler(local).waiting()).isEqualTo(ReadGate.Wait.STREAM);
        assertThat(gate().blockedOnSession()).isEmpty();
    }

    @Test
    void itsStreamsWindowUpdateResumesIt() {
        DuplexEmbeddedChannel local = connect();
        read(local, WINDOW);
        drain();
        connector.onWindowUpdate(new Frame.WindowUpdate(StreamIds.CONTROL, WINDOW));
        assertThat(local.config().isAutoRead())
                .as("session credit doesn't resume a stream waiting for its own")
                .isFalse();

        connector.onWindowUpdate(new Frame.WindowUpdate(1, 1_000));

        assertThat(local.config().isAutoRead()).isTrue();
        assertThat(handler(local).waiting()).isNull();
        assertThat(gate().sendable(handler(local))).isEqualTo(1_000);
    }

    @Test
    void whenTheSessionRunsOutOtherConnectionsArePausedOnlyWhenNextReadAndCarryOneByte() {
        DuplexEmbeddedChannel first = connect();
        DuplexEmbeddedChannel second = connect();
        read(first, WINDOW);
        assertThat(second.config().isAutoRead()).as("not paused in advance").isTrue();
        drain();

        ByteBuf oneByte = ascii("x");
        read(second, oneByte);

        assertThat(dataSent()).isEmpty();
        assertThat(oneByte.refCnt()).as("carried, not released").isOne();
        assertThat(second.config().isAutoRead()).isFalse();
        assertThat(handler(second).waiting()).isEqualTo(ReadGate.Wait.SESSION);
        assertThat(gate().blockedOnSession()).containsExactly(handler(second));
    }

    @Test
    void sessionCreditSendsTheCarriedByteFirstThenReadsTheConnectionAgain() {
        DuplexEmbeddedChannel first = connect();
        DuplexEmbeddedChannel second = connect();
        read(first, WINDOW);
        drain();
        read(second, ascii("x"));

        connector.onWindowUpdate(new Frame.WindowUpdate(StreamIds.CONTROL, 10));
        connector.onReadComplete();

        Frame.Data carried = control.readOutbound();
        assertThat(carried.streamId()).isEqualTo(2);
        assertThat(carried.payload().toString(StandardCharsets.US_ASCII)).isEqualTo("x");
        carried.release();
        assertThat(second.config().isAutoRead()).isTrue();
        assertThat(gate().sendable(handler(second))).isEqualTo(9);
        assertThat(gate().blockedOnSession())
                .as("in line until a read of its own goes out")
                .containsExactly(handler(second));

        read(second, 9);

        assertThat(gate().blockedOnSession()).containsExactly(handler(second));
        assertThat(handler(second).waiting()).isEqualTo(ReadGate.Wait.SESSION);
    }

    @Test
    void eachConnectionResumedFromTheLineMayTakeAnEqualShareOfTheCreditForItsFirstRead() {
        DuplexEmbeddedChannel first = connect();
        read(first, WINDOW);
        drain();
        List<DuplexEmbeddedChannel> waiting = List.of(connect(), connect(), connect());
        for (DuplexEmbeddedChannel local : waiting) {
            read(local, ascii("x"));
        }

        connector.onWindowUpdate(new Frame.WindowUpdate(StreamIds.CONTROL, 102));
        connector.onReadComplete();

        assertThat(dataSent()).containsExactly(1, 1, 1);
        for (DuplexEmbeddedChannel local : waiting) {
            assertThat(gate().sendable(handler(local))).isEqualTo(34);
        }
        read(waiting.get(0), 34);
        assertThat(gate().sendable(handler(waiting.get(0))))
                .as("the share lifts once its read is sent")
                .isEqualTo(99 - 34);
        assertThat(gate().sendable(handler(waiting.get(1)))).isEqualTo(34);
    }

    @Test
    void aConnectionWhoseReadSpendsTheCreditLeavesTheLineAndRejoinsAtTheBack() {
        DuplexEmbeddedChannel first = connect();
        read(first, WINDOW);
        drain();
        List<DuplexEmbeddedChannel> waiting = List.of(connect(), connect(), connect());
        for (DuplexEmbeddedChannel local : waiting) {
            read(local, ascii("x"));
        }

        connector.onWindowUpdate(new Frame.WindowUpdate(StreamIds.CONTROL, 4));
        connector.onReadComplete();
        assertThat(dataSent()).containsExactly(1, 1, 1);
        read(waiting.get(0), 1);
        read(waiting.get(1), ascii("y"));
        read(waiting.get(2), ascii("z"));

        assertThat(dataSent()).containsExactly(1);
        assertThat(gate().blockedOnSession())
                .containsExactly(handler(waiting.get(1)), handler(waiting.get(2)), handler(waiting.get(0)));
    }

    @Test
    void connectionsInLineResumeInLineOrderUntilTheSessionRunsOutAgain() {
        DuplexEmbeddedChannel first = connect();
        read(first, WINDOW);
        drain();
        List<DuplexEmbeddedChannel> waiting = List.of(connect(), connect(), connect());
        for (DuplexEmbeddedChannel local : waiting) {
            read(local, ascii("x"));
        }
        assertThat(gate().blockedOnSession())
                .containsExactly(handler(waiting.get(0)), handler(waiting.get(1)), handler(waiting.get(2)));

        connector.onWindowUpdate(new Frame.WindowUpdate(StreamIds.CONTROL, 2));
        connector.onReadComplete();

        assertThat(dataSent()).containsExactly(1, 1);
        assertThat(waiting.get(0).config().isAutoRead()).isTrue();
        assertThat(waiting.get(1).config().isAutoRead())
                .as("its byte took the last credit")
                .isFalse();
        assertThat(waiting.get(2).config().isAutoRead()).as("not reached").isFalse();
        assertThat(gate().blockedOnSession())
                .as("every connection keeps its place until a read of its own goes out")
                .containsExactly(handler(waiting.get(0)), handler(waiting.get(1)), handler(waiting.get(2)));
        assertThat(handler(waiting.get(1)).hasCarry()).isFalse();
        assertThat(handler(waiting.get(2)).hasCarry()).isTrue();
    }

    @Test
    void aFullControlChannelPausesAConnectionWhenNextReadAndItsDrainingResumesIt() {
        DuplexEmbeddedChannel local = connect();
        control.config().setWriteBufferWaterMark(new WriteBufferWaterMark(1_024, 4_096));
        control.write(Unpooled.wrappedBuffer(new byte[4_097]));
        assertThat(control.isWritable()).isFalse();
        assertThat(gate().sendable(handler(local))).isZero();

        read(local, ascii("x"));

        assertThat(handler(local).waiting()).isEqualTo(ReadGate.Wait.HEADROOM);
        assertThat(gate().blockedOnSession()).containsExactly(handler(local));

        control.flush();
        ReferenceCountUtil.release(control.readOutbound());
        connector.onControlWritabilityChanged(true);

        assertThat(dataSent()).containsExactly(1);
        assertThat(local.config().isAutoRead()).isTrue();
    }

    @Test
    void aFinBehindACarriedByteIsSentAfterIt() {
        DuplexEmbeddedChannel first = connect();
        DuplexEmbeddedChannel second = connect();
        read(first, WINDOW);
        drain();
        read(second, ascii("x"));

        second.receiveFin();
        assertThat((Object) control.readOutbound())
                .as("the CLOSE waits for the byte")
                .isNull();

        connector.onWindowUpdate(new Frame.WindowUpdate(StreamIds.CONTROL, 1));
        connector.onReadComplete();

        Frame.Data carried = control.readOutbound();
        assertThat(carried.payload().readableBytes()).isOne();
        carried.release();
        assertThat((Frame.Close) control.readOutbound()).isEqualTo(Frame.Close.half(2));
        assertThat(gate().blockedOnSession()).isEmpty();
        assertThat(handler(second).waiting()).isNull();
    }

    @Test
    void aResetReleasesTheCarriedBytesWithoutWaitingForCredit() {
        DuplexEmbeddedChannel first = connect();
        DuplexEmbeddedChannel second = connect();
        read(first, WINDOW);
        drain();
        ByteBuf carried = ascii("x");
        read(second, carried);

        connector.onClose(Frame.Close.reset(2, ErrorCode.PEER_RESET));

        assertThat(carried.refCnt()).isZero();
        assertThat(gate().blockedOnSession()).isEmpty();
        assertThat(second.isOpen()).isFalse();
    }

    @Test
    void cancellingTheSessionReleasesEveryCarry() {
        DuplexEmbeddedChannel first = connect();
        DuplexEmbeddedChannel second = connect();
        read(first, WINDOW);
        drain();
        ByteBuf carried = ascii("x");
        read(second, carried);

        connector.cancelAll(true);
        control.flush();

        assertThat(carried.refCnt()).isZero();
    }

    @Test
    void bytesReadPastCreditQueueBehindTheCarryAndGoOutInOrder() {
        DuplexEmbeddedChannel first = connect();
        DuplexEmbeddedChannel second = connect();
        read(first, WINDOW);
        drain();
        read(second, ascii("a"));

        read(second, ascii("bcdef"));
        assertThat(handler(second).carried()).isEqualTo(6);
        connector.onWindowUpdate(new Frame.WindowUpdate(StreamIds.CONTROL, 4));
        connector.onReadComplete();

        assertThat(dataPayloads()).containsExactly("abcd");
        assertThat(handler(second).carried()).isEqualTo(2);
        connector.onWindowUpdate(new Frame.WindowUpdate(StreamIds.CONTROL, 100));
        connector.onReadComplete();
        assertThat(dataPayloads()).containsExactly("ef");
        assertThat(second.config().isAutoRead()).isTrue();
    }

    @Test
    void aConnectionWhoseInputIsShutDownIsNeverReadAgain() {
        DuplexEmbeddedChannel first = connect();
        DuplexEmbeddedChannel second = connect();
        read(first, WINDOW);
        drain();
        read(second, ascii("x"));
        second.receiveFin();

        connector.onWindowUpdate(new Frame.WindowUpdate(StreamIds.CONTROL, 100));

        assertThat(second.config().isAutoRead()).isFalse();
    }

    @Test
    void aConnectionMadeWithNoSessionCreditStartsPausedAndWaiting() {
        DuplexEmbeddedChannel first = connect();
        read(first, WINDOW);
        drain();

        DuplexEmbeddedChannel late = connect();

        assertThat(late.config().isAutoRead()).isFalse();
        assertThat(gate().blockedOnSession()).containsExactly(handler(late));
    }

    // The client's own: streams whose local connection is still being made, and the server's DATA.

    @Test
    void creditForAStreamStillConnectingWaitsUntilItsConnectionIsMade() {
        Promise<Channel> connecting = control.eventLoop().newPromise();
        dials.add(connecting);
        connector.open(open(nextStreamId++));

        connector.onWindowUpdate(new Frame.WindowUpdate(1, 1_000));
        connector.onWindowUpdate(new Frame.WindowUpdate(StreamIds.CONTROL, 1_000));
        assertThat(gate().blockedOnSession()).isEmpty();

        DuplexEmbeddedChannel local = local();
        connecting.setSuccess(local);

        assertThat(local.config().isAutoRead()).isTrue();
        assertThat(gate().sendable(handler(local))).isEqualTo(WINDOW);
    }

    @Test
    void dataForAStreamStillConnectingIsHeldAndReturnsCreditOnlyOnceWritten() {
        Promise<Channel> connecting = control.eventLoop().newPromise();
        dials.add(connecting);
        connector.open(open(nextStreamId++));
        int half = ClientSettings.DEFAULT_WINDOWS.stream() / 2;

        connector.onData(new Frame.Data(1, Unpooled.wrappedBuffer(new byte[half])));
        connector.onReadComplete();
        assertThat((Object) control.readOutbound())
                .as("nothing is released before it is written")
                .isNull();

        DuplexEmbeddedChannel local = local();
        connecting.setSuccess(local);

        assertThat(local.<ByteBuf>readOutbound().readableBytes()).isEqualTo(half);
        assertThat((Frame.WindowUpdate) control.readOutbound()).isEqualTo(new Frame.WindowUpdate(1, half));
    }

    @Test
    void dataHeldForAConnectionThatFailsReturnsItsSessionCredit() {
        Promise<Channel> connecting = control.eventLoop().newPromise();
        dials.add(connecting);
        connector.open(open(nextStreamId++));
        int stream = ClientSettings.DEFAULT_WINDOWS.stream();
        ByteBuf held = Unpooled.wrappedBuffer(new byte[stream]);
        connector.onData(new Frame.Data(1, held));

        connecting.setFailure(new ConnectException("refused"));
        assertThat((Frame.Close) control.readOutbound())
                .isEqualTo(Frame.Close.reset(1, ErrorCode.LOCAL_SERVICE_UNREACHABLE));
        assertThat(held.refCnt()).isZero();

        connector.onData(new Frame.Data(1, Unpooled.wrappedBuffer(new byte[stream])));

        assertThat((Frame.WindowUpdate) control.readOutbound())
                .as("the held bytes and the discarded ones, at half the session window")
                .isEqualTo(new Frame.WindowUpdate(StreamIds.CONTROL, 2 * stream));
    }

    // Helpers.

    private ReadGate gate() {
        return connector.gate();
    }

    /** Opens the next stream, whose local connection is made at once. */
    private DuplexEmbeddedChannel connect() {
        DuplexEmbeddedChannel local = local();
        dials.add(control.eventLoop().newSucceededFuture(local));
        connector.open(open(nextStreamId++));
        return local;
    }

    /** A local connection as the dialer hands it over: not reading, no handlers. */
    private DuplexEmbeddedChannel local() {
        DuplexEmbeddedChannel local = new DuplexEmbeddedChannel(LOCAL);
        local.config().setAutoRead(false);
        locals.add(local);
        return local;
    }

    private Future<Channel> dial(LocalTarget target) {
        return dials.remove();
    }

    private static Frame.Open open(int streamId) {
        return new Frame.Open(streamId, new Open(TUNNEL_ID, TunnelKind.TCP, new RemoteAddress("203.0.113.9", 51_234)));
    }

    private static LocalRelayHandler handler(DuplexEmbeddedChannel local) {
        return local.pipeline().get(LocalRelayHandler.class);
    }

    private void read(DuplexEmbeddedChannel local, int bytes) {
        read(local, Unpooled.wrappedBuffer(new byte[bytes]));
    }

    /** One read batch from the local connection, as its event loop delivers it. */
    private static void read(DuplexEmbeddedChannel local, ByteBuf bytes) {
        local.pipeline().fireChannelRead(bytes);
        local.pipeline().fireChannelReadComplete();
    }

    /** The payload length of each {@code DATA} sent to the server so far, releasing each frame. */
    private List<Integer> dataSent() {
        List<Integer> lengths = new ArrayList<>();
        for (Object frame = control.readOutbound(); frame != null; frame = control.readOutbound()) {
            if (frame instanceof Frame.Data data) {
                lengths.add(data.payload().readableBytes());
            }
            ReferenceCountUtil.release(frame);
        }
        return lengths;
    }

    private List<String> dataPayloads() {
        List<String> payloads = new ArrayList<>();
        for (Object frame = control.readOutbound(); frame != null; frame = control.readOutbound()) {
            if (frame instanceof Frame.Data data) {
                payloads.add(data.payload().toString(StandardCharsets.US_ASCII));
            }
            ReferenceCountUtil.release(frame);
        }
        return payloads;
    }

    private void drain() {
        dataSent();
    }

    private static ByteBuf ascii(String text) {
        return Unpooled.copiedBuffer(text, StandardCharsets.US_ASCII);
    }
}
