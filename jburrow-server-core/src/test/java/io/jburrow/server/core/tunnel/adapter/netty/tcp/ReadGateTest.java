package io.jburrow.server.core.tunnel.adapter.netty.tcp;

import static org.assertj.core.api.Assertions.assertThat;

import io.jburrow.protocol.adapter.netty.Frame;
import io.jburrow.protocol.control.ClientInfo;
import io.jburrow.protocol.control.Hello;
import io.jburrow.protocol.control.Windows;
import io.jburrow.protocol.testsupport.DuplexEmbeddedChannel;
import io.jburrow.protocol.wire.ErrorCode;
import io.jburrow.protocol.wire.StreamIds;
import io.jburrow.server.core.tunnel.adapter.netty.control.SessionCloser;
import io.jburrow.server.core.tunnel.adapter.netty.control.SessionContext;
import io.jburrow.server.core.tunnel.application.RecordingTunnelMetrics;
import io.jburrow.server.core.tunnel.application.SessionAdmission;
import io.jburrow.server.core.tunnel.application.StreamQuota;
import io.jburrow.server.core.tunnel.domain.Principal;
import io.jburrow.server.core.tunnel.domain.Session;
import io.jburrow.server.core.tunnel.domain.SessionId;
import io.jburrow.server.core.tunnel.domain.Tunnel;
import io.jburrow.server.core.tunnel.domain.TunnelId;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.RecvByteBufAllocator;
import io.netty.channel.WriteBufferWaterMark;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.ReferenceCountUtil;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@link ReadGate}'s rules one by one, {@code docs/phase-2-multiplexing/ARCHITECTURE.md} §Visitor to client: the read
 * gate, through the relay that owns it, with no sockets. The client's windows are the smallest allowed, 64 KiB for a
 * stream and for the session, so one full read spends either.
 */
class ReadGateTest {

    private static final int WINDOW = 65_536;
    private static final Tunnel TUNNEL = Tunnel.tcp(TunnelId.random(), "default", "localhost", 21_037);
    private static final InetSocketAddress VISITOR = new InetSocketAddress("203.0.113.9", 51_234);
    private static final WriteBufferWaterMark ROOMY = new WriteBufferWaterMark(1024 * 1024, 2 * 1024 * 1024);

    private final RecordingTunnelMetrics metrics = new RecordingTunnelMetrics();
    private final AtomicLong now = new AtomicLong();
    private final List<EmbeddedChannel> visitors = new ArrayList<>();

    private EmbeddedChannel sessionChannel;
    private Session session;
    private StreamRelay relay;

    @BeforeEach
    void setUp() {
        sessionChannel = new EmbeddedChannel();
        sessionChannel.config().setWriteBufferWaterMark(ROOMY);
        session = new Session(SessionId.random(), 8, new Windows(262_144, 524_288));
        session.onHello(new Hello(
                new ClientInfo("jburrow-test", "1.0", "linux", "amd64"), List.of(), new Windows(WINDOW, WINDOW)));
        session.onRegisterReceived();
        session.onEstablished(new Principal("tester"), List.of(TUNNEL));
        SessionContext context = new SessionContext(
                session,
                sessionChannel,
                new SessionAdmission(1, 1, 1).tryBeginHandshake().orElseThrow(),
                InetAddress.getLoopbackAddress(),
                0,
                new SessionCloser(metrics));
        relay = new StreamRelay(context, metrics, new StreamQuota(8), now::get, Duration.ofSeconds(10));
        context.streams(relay);
    }

    @AfterEach
    void tearDown() {
        visitors.forEach(EmbeddedChannel::finishAndReleaseAll);
        sessionChannel.finishAndReleaseAll();
    }

    @Test
    void eachVisitorsReadsAreSizedByWhatItsStreamMaySendNow() {
        DuplexEmbeddedChannel visitor = connect();
        RecvByteBufAllocator.Handle reads =
                visitor.config().getRecvByteBufAllocator().newHandle();
        reads.reset(visitor.config());
        assertThat(reads.guess()).isEqualTo(2_048);

        read(visitor, WINDOW - 100);

        assertThat(relay.gate().sendable(handler(visitor))).isEqualTo(100);
        assertThat(reads.guess()).isEqualTo(100);
    }

    @Test
    void aVisitorWhoseReadSpendsItsStreamCreditIsPausedAtOnceAndCountedOnce() {
        DuplexEmbeddedChannel visitor = connect();

        read(visitor, WINDOW);

        assertThat(dataSent()).containsExactly(WINDOW);
        assertThat(visitor.config().isAutoRead()).isFalse();
        assertThat(handler(visitor).waiting()).isEqualTo(ReadGate.Wait.STREAM);
        assertThat(relay.gate().blockedOnSession()).isEmpty();
        assertThat(metrics.events()).containsOnlyOnce("flowBlocked STREAM");
    }

    @Test
    void itsStreamsWindowUpdateResumesIt() {
        DuplexEmbeddedChannel visitor = connect();
        read(visitor, WINDOW);
        drain();
        relay.onWindowUpdate(new Frame.WindowUpdate(StreamIds.CONTROL, WINDOW));
        assertThat(visitor.config().isAutoRead())
                .as("session credit doesn't resume a stream waiting for its own")
                .isFalse();

        relay.onWindowUpdate(new Frame.WindowUpdate(1, 1_000));

        assertThat(visitor.config().isAutoRead()).isTrue();
        assertThat(handler(visitor).waiting()).isNull();
        assertThat(relay.gate().sendable(handler(visitor))).isEqualTo(1_000);
    }

    @Test
    void whenTheSessionRunsOutOtherVisitorsArePausedOnlyWhenNextReadAndCarryOneByte() {
        DuplexEmbeddedChannel first = connect();
        DuplexEmbeddedChannel second = connect();
        read(first, WINDOW);
        assertThat(second.config().isAutoRead()).as("not paused in advance").isTrue();
        drain();

        ByteBuf oneByte = ascii("x");
        read(second, oneByte);

        assertThat(dataSent()).isEmpty();
        assertThat(oneByte.refCnt()).as("copied onto the heap and released").isZero();
        assertThat(handler(second).carried()).isOne();
        assertThat(second.config().isAutoRead()).isFalse();
        assertThat(handler(second).waiting()).isEqualTo(ReadGate.Wait.SESSION);
        assertThat(relay.gate().blockedOnSession()).containsExactly(handler(second));
        assertThat(metrics.events()).containsOnlyOnce("flowBlocked SESSION");
    }

    @Test
    void sessionCreditSendsTheCarriedByteFirstThenReadsTheVisitorAgain() {
        DuplexEmbeddedChannel first = connect();
        DuplexEmbeddedChannel second = connect();
        read(first, WINDOW);
        drain();
        read(second, ascii("x"));

        relay.onWindowUpdate(new Frame.WindowUpdate(StreamIds.CONTROL, 10));
        relay.onReadComplete();

        Frame.Data carried = sessionChannel.readOutbound();
        assertThat(carried.streamId()).isEqualTo(2);
        assertThat(carried.payload().toString(StandardCharsets.US_ASCII)).isEqualTo("x");
        carried.release();
        assertThat(second.config().isAutoRead()).isTrue();
        assertThat(relay.gate().sendable(handler(second))).isEqualTo(9);
        assertThat(relay.gate().blockedOnSession())
                .as("in line until a read of its own goes out")
                .containsExactly(handler(second));

        read(second, 9);

        assertThat(relay.gate().blockedOnSession()).containsExactly(handler(second));
        assertThat(handler(second).waiting()).isEqualTo(ReadGate.Wait.SESSION);
    }

    @Test
    void eachVisitorResumedFromTheLineMayTakeAnEqualShareOfTheCreditForItsFirstRead() {
        List<DuplexEmbeddedChannel> waiting = waitingInLine(3);

        relay.onWindowUpdate(new Frame.WindowUpdate(StreamIds.CONTROL, WINDOW));
        relay.onReadComplete();

        assertThat(dataSent()).containsExactly(1, 1, 1);
        for (DuplexEmbeddedChannel visitor : waiting) {
            assertThat(relay.gate().sendable(handler(visitor))).isEqualTo(WINDOW / 3);
        }
        read(waiting.get(0), WINDOW / 3);
        assertThat(relay.gate().sendable(handler(waiting.get(0))))
                .as("the share lifts once its read is sent")
                .isEqualTo(WINDOW - 3 - WINDOW / 3);
        assertThat(relay.gate().sendable(handler(waiting.get(1)))).isEqualTo(WINDOW / 3);
    }

    @Test
    void aShareIsNeverBelowTheFloorAndOnlyTheVisitorsTheCreditCoversAreResumed() {
        List<DuplexEmbeddedChannel> waiting = waitingInLine(5);

        relay.onWindowUpdate(new Frame.WindowUpdate(StreamIds.CONTROL, WINDOW));
        relay.onReadComplete();

        assertThat(dataSent()).containsExactly(1, 1, 1, 1);
        for (DuplexEmbeddedChannel visitor : waiting.subList(0, 4)) {
            assertThat(visitor.config().isAutoRead()).isTrue();
            assertThat(relay.gate().sendable(handler(visitor))).isEqualTo(ReadGate.SHARE_FLOOR);
        }
        assertThat(waiting.get(4).config().isAutoRead())
                .as("four floors cover the credit")
                .isFalse();
        assertThat(handler(waiting.get(4)).hasCarry()).isTrue();
        assertThat(relay.gate().blockedOnSession()).hasSize(5);
    }

    @Test
    void creditTheResumedVisitorsLeaveUnusedGoesToTheNextInLineShortlyAfter() {
        List<DuplexEmbeddedChannel> waiting = waitingInLine(5);
        relay.onWindowUpdate(new Frame.WindowUpdate(StreamIds.CONTROL, WINDOW));
        relay.onReadComplete();
        for (DuplexEmbeddedChannel visitor : waiting.subList(0, 4)) {
            read(visitor, 100);
        }
        drain();

        recheck();

        assertThat(dataSent()).as("the last visitor's carried byte, flushed").containsExactly(1);
        assertThat(waiting.get(4).config().isAutoRead()).isTrue();
    }

    @Test
    void aVisitorResumedFromTheLineGetsOneReadThenWaitsBehindThoseStillPaused() {
        List<DuplexEmbeddedChannel> waiting = waitingInLine(5);
        relay.onWindowUpdate(new Frame.WindowUpdate(StreamIds.CONTROL, WINDOW));
        relay.onReadComplete();
        drain();
        int pausesCounted = metrics.events().size();

        read(waiting.get(0), 100);

        assertThat(dataSent()).containsExactly(100);
        assertThat(waiting.get(0).config().isAutoRead())
                .as("credit is left, but the last visitor hasn't had a turn")
                .isFalse();
        assertThat(handler(waiting.get(0)).waiting()).isEqualTo(ReadGate.Wait.TURN);
        assertThat(relay.gate().blockedOnSession()).endsWith(handler(waiting.get(0)));
        assertThat(metrics.events()).as("a turn isn't a pause for credit").hasSize(pausesCounted);

        recheck();

        assertThat(waiting.get(4).config().isAutoRead()).isTrue();
    }

    @Test
    void aPausedVisitorReadAnywayWaitsForNothingOrRejoinsTheLine() {
        List<DuplexEmbeddedChannel> waiting = waitingInLine(5);
        relay.onWindowUpdate(new Frame.WindowUpdate(StreamIds.CONTROL, WINDOW));
        relay.onReadComplete();
        read(waiting.get(0), ReadGate.SHARE_FLOOR);
        drain();
        assertThat(handler(waiting.get(0)).waiting()).isEqualTo(ReadGate.Wait.TURN);

        read(waiting.get(0), 100);

        assertThat(dataSent()).as("the transport had found it readable").containsExactly(100);
        assertThat(handler(waiting.get(0)).waiting()).isNull();
        assertThat(waiting.get(0).config().isAutoRead()).isTrue();

        read(waiting.get(0), WINDOW - 4 - ReadGate.SHARE_FLOOR - 100);

        assertThat(handler(waiting.get(0)).waiting()).isEqualTo(ReadGate.Wait.SESSION);
        assertThat(relay.gate().blockedOnSession())
                .as("back in line, where credit will find it")
                .endsWith(handler(waiting.get(0)));
    }

    @Test
    void aVisitorWhoseReadSpendsTheCreditLeavesTheLineAndRejoinsAtTheBack() {
        List<DuplexEmbeddedChannel> waiting = waitingInLine(3);

        relay.onWindowUpdate(new Frame.WindowUpdate(StreamIds.CONTROL, 4));
        relay.onReadComplete();
        assertThat(dataSent()).containsExactly(1);
        read(waiting.get(0), 3);

        assertThat(dataSent()).containsExactly(3);
        assertThat(relay.gate().blockedOnSession())
                .containsExactly(handler(waiting.get(1)), handler(waiting.get(2)), handler(waiting.get(0)));
    }

    @Test
    void visitorsInLineResumeInLineOrderUntilTheSessionRunsOutAgain() {
        List<DuplexEmbeddedChannel> waiting = waitingInLine(3);
        assertThat(relay.gate().blockedOnSession())
                .containsExactly(handler(waiting.get(0)), handler(waiting.get(1)), handler(waiting.get(2)));

        relay.onWindowUpdate(new Frame.WindowUpdate(StreamIds.CONTROL, 2));
        relay.onReadComplete();

        assertThat(dataSent()).containsExactly(1);
        assertThat(waiting.get(0).config().isAutoRead()).isTrue();
        assertThat(waiting.get(1).config().isAutoRead())
                .as("the first visitor may send the rest")
                .isFalse();

        recheck();

        assertThat(dataSent()).as("the first visitor left its credit unused").containsExactly(1);
        assertThat(waiting.get(1).config().isAutoRead())
                .as("its byte took the last credit")
                .isFalse();
        assertThat(waiting.get(2).config().isAutoRead()).as("not reached").isFalse();
        assertThat(relay.gate().blockedOnSession())
                .as("every visitor keeps its place until a read of its own goes out")
                .containsExactly(handler(waiting.get(0)), handler(waiting.get(1)), handler(waiting.get(2)));
        assertThat(handler(waiting.get(1)).hasCarry()).isFalse();
        assertThat(handler(waiting.get(2)).hasCarry()).isTrue();
    }

    @Test
    void aFullSessionChannelPausesAVisitorWhenNextReadAndItsDrainingResumesIt() {
        DuplexEmbeddedChannel visitor = connect();
        sessionChannel.config().setWriteBufferWaterMark(new WriteBufferWaterMark(1_024, 4_096));
        sessionChannel.write(Unpooled.wrappedBuffer(new byte[4_097]));
        assertThat(sessionChannel.isWritable()).isFalse();
        assertThat(relay.gate().sendable(handler(visitor))).isZero();

        read(visitor, ascii("x"));

        assertThat(handler(visitor).waiting()).isEqualTo(ReadGate.Wait.HEADROOM);
        assertThat(metrics.events()).containsOnlyOnce("backpressurePaused INBOUND");
        assertThat(metrics.events()).noneMatch(event -> event.startsWith("flowBlocked"));

        sessionChannel.flush();
        ReferenceCountUtil.release(sessionChannel.readOutbound());
        relay.onSessionWritabilityChanged(true);

        assertThat(dataSent()).containsExactly(1);
        assertThat(visitor.config().isAutoRead()).isTrue();
    }

    @Test
    void aFinBehindACarriedByteIsSentAfterIt() {
        DuplexEmbeddedChannel first = connect();
        DuplexEmbeddedChannel second = connect();
        read(first, WINDOW);
        drain();
        read(second, ascii("x"));

        second.receiveFin();
        assertThat((Object) sessionChannel.readOutbound())
                .as("the CLOSE waits for the byte")
                .isNull();

        relay.onWindowUpdate(new Frame.WindowUpdate(StreamIds.CONTROL, 1));
        relay.onReadComplete();

        Frame.Data carried = sessionChannel.readOutbound();
        assertThat(carried.payload().readableBytes()).isOne();
        carried.release();
        assertThat((Frame.Close) sessionChannel.readOutbound()).isEqualTo(Frame.Close.half(2));
        assertThat(relay.gate().blockedOnSession()).isEmpty();
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

        relay.onClose(Frame.Close.reset(2, ErrorCode.PEER_RESET));

        assertThat(carried.refCnt()).isZero();
        assertThat(relay.gate().blockedOnSession()).isEmpty();
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

        relay.cancelAll();
        sessionChannel.flush();

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
        relay.onWindowUpdate(new Frame.WindowUpdate(StreamIds.CONTROL, 4));
        relay.onReadComplete();

        assertThat(dataPayloads()).containsExactly("abcd");
        assertThat(handler(second).carried()).isEqualTo(2);
        relay.onWindowUpdate(new Frame.WindowUpdate(StreamIds.CONTROL, 100));
        relay.onReadComplete();
        assertThat(dataPayloads()).containsExactly("ef");
        assertThat(second.config().isAutoRead()).isTrue();
    }

    @Test
    void aVisitorWhoseInputIsShutDownIsNeverReadAgain() {
        DuplexEmbeddedChannel first = connect();
        DuplexEmbeddedChannel second = connect();
        read(first, WINDOW);
        drain();
        read(second, ascii("x"));
        second.receiveFin();

        relay.onWindowUpdate(new Frame.WindowUpdate(StreamIds.CONTROL, 100));

        assertThat(second.config().isAutoRead()).isFalse();
    }

    @Test
    void theTimeAStreamSpendsBlockedIsAddedUpWhenItResumesOrEnds() {
        DuplexEmbeddedChannel visitor = connect();
        VisitorHandler handler = handler(visitor);
        read(visitor, WINDOW);
        relay.onWindowUpdate(new Frame.WindowUpdate(StreamIds.CONTROL, 100));
        now.addAndGet(Duration.ofMillis(250).toNanos());
        relay.onWindowUpdate(new Frame.WindowUpdate(1, 100));
        assertThat(handler.blockedNanos()).isEqualTo(Duration.ofMillis(250).toNanos());

        read(visitor, 100);
        now.addAndGet(Duration.ofMillis(50).toNanos());
        relay.onClose(Frame.Close.reset(1, ErrorCode.CANCELLED));

        assertThat(handler.blockedNanos()).isEqualTo(Duration.ofMillis(300).toNanos());
    }

    @Test
    void aVisitorOpenedWithNoSessionCreditStartsPausedAndWaiting() {
        DuplexEmbeddedChannel first = connect();
        read(first, WINDOW);
        drain();

        DuplexEmbeddedChannel late = connect();

        assertThat(late.config().isAutoRead()).isFalse();
        assertThat(relay.gate().blockedOnSession()).containsExactly(handler(late));
    }

    /** {@code count} visitors in line behind one that spent the session's credit, each carrying a byte. */
    private List<DuplexEmbeddedChannel> waitingInLine(int count) {
        read(connect(), WINDOW);
        drain();
        List<DuplexEmbeddedChannel> waiting = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            DuplexEmbeddedChannel visitor = connect();
            read(visitor, ascii("x"));
            waiting.add(visitor);
        }
        return waiting;
    }

    /** Runs the gate's look for unused credit after a partial resume. */
    private void recheck() {
        sessionChannel.advanceTimeBy(ReadGate.RECHECK_NANOS, TimeUnit.NANOSECONDS);
        sessionChannel.runScheduledPendingTasks();
    }

    private DuplexEmbeddedChannel connect() {
        DuplexEmbeddedChannel visitor = new DuplexEmbeddedChannel(VISITOR, new VisitorHandler(relay, TUNNEL));
        visitors.add(visitor);
        assertThat((Object) sessionChannel.readOutbound()).isInstanceOf(Frame.Open.class);
        return visitor;
    }

    private static VisitorHandler handler(DuplexEmbeddedChannel visitor) {
        return visitor.pipeline().get(VisitorHandler.class);
    }

    private void read(DuplexEmbeddedChannel visitor, int bytes) {
        read(visitor, Unpooled.wrappedBuffer(new byte[bytes]));
    }

    /** One read batch from the visitor, as its event loop delivers it. */
    private static void read(DuplexEmbeddedChannel visitor, ByteBuf bytes) {
        visitor.pipeline().fireChannelRead(bytes);
        visitor.pipeline().fireChannelReadComplete();
    }

    /** The payload length of each {@code DATA} sent to the client so far, releasing each frame. */
    private List<Integer> dataSent() {
        List<Integer> lengths = new ArrayList<>();
        for (Object frame = sessionChannel.readOutbound(); frame != null; frame = sessionChannel.readOutbound()) {
            if (frame instanceof Frame.Data data) {
                lengths.add(data.payload().readableBytes());
            }
            ReferenceCountUtil.release(frame);
        }
        return lengths;
    }

    private List<String> dataPayloads() {
        List<String> payloads = new ArrayList<>();
        for (Object frame = sessionChannel.readOutbound(); frame != null; frame = sessionChannel.readOutbound()) {
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
