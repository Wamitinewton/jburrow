package io.jburrow.server.core.tunnel.adapter.netty.tcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.jburrow.protocol.adapter.netty.Frame;
import io.jburrow.protocol.control.ClientInfo;
import io.jburrow.protocol.control.Hello;
import io.jburrow.protocol.control.RemoteAddress;
import io.jburrow.protocol.control.TunnelKind;
import io.jburrow.protocol.control.Windows;
import io.jburrow.protocol.stream.StreamState;
import io.jburrow.protocol.testsupport.DuplexEmbeddedChannel;
import io.jburrow.protocol.wire.ErrorCode;
import io.jburrow.protocol.wire.ProtocolException;
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
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.ReferenceCountUtil;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The relay table of {@code docs/phase-1-tcp-tunnel/ARCHITECTURE.md} §The data path, row by row, with no sockets, and
 * the client-to-visitor half of {@code docs/phase-2-multiplexing/ARCHITECTURE.md} §Client to visitor: credit on
 * delivery. The read gate has its own test, {@link ReadGateTest}.
 */
class StreamRelayTest {

    private static final Tunnel TUNNEL = new Tunnel(TunnelId.random(), "default", TunnelKind.TCP, "localhost", 21_037);
    private static final InetSocketAddress VISITOR = new InetSocketAddress("203.0.113.9", 51_234);

    private final RecordingTunnelMetrics metrics = new RecordingTunnelMetrics();
    private final StreamQuota streamQuota = new StreamQuota(2);
    private final List<EmbeddedChannel> visitors = new ArrayList<>();

    private EmbeddedChannel sessionChannel;
    private Session session;
    private SessionContext context;
    private StreamRelay relay;

    @BeforeEach
    void setUp() {
        sessionChannel = new EmbeddedChannel();
        session = new Session(SessionId.random(), 1, new Windows(262_144, 524_288));
        session.onHello(new Hello(
                new ClientInfo("jburrow-test", "1.0", "linux", "amd64"), List.of(), new Windows(262_144, 1_048_576)));
        session.onRegisterReceived();
        session.onEstablished(new Principal("tester"), List.of(TUNNEL));
        context = new SessionContext(
                session,
                sessionChannel,
                new SessionAdmission(1, 1, 1).tryBeginHandshake().orElseThrow(),
                InetAddress.getLoopbackAddress(),
                0,
                new SessionCloser(metrics));
        relay = new StreamRelay(context, metrics, streamQuota);
        context.streams(relay);
    }

    @AfterEach
    void tearDown() {
        visitors.forEach(EmbeddedChannel::finishAndReleaseAll);
        sessionChannel.finishAndReleaseAll();
    }

    @Test
    void anAdmittedVisitorGetsOpenNamingItsTunnelAndAddressThenIsRead() {
        DuplexEmbeddedChannel visitor = connect();

        Frame.Open open = sessionChannel.readOutbound();
        assertThat(open.streamId()).isEqualTo(1);
        assertThat(open.message().tunnelId()).isEqualTo(TUNNEL.id().value());
        assertThat(open.message().kind()).isEqualTo(TunnelKind.TCP);
        assertThat(open.message().remote()).isEqualTo(new RemoteAddress("203.0.113.9", 51_234));
        assertThat(visitor.config().isAutoRead()).isTrue();
        assertThat(metrics.events()).contains("streamOpened TCP");
    }

    @Test
    void aSecondVisitorIsRefusedWhileTheFirstIsConnected() {
        connectAndOpen();

        DuplexEmbeddedChannel second = connect();

        assertThat(second.isOpen()).isFalse();
        assertThat((Object) sessionChannel.readOutbound()).isNull();
        assertThat(metrics.events()).contains("visitorRefused STREAM_LIMIT");
        assertThat(streamQuota.inUse()).isOne();
    }

    @Test
    void aVisitorIsRefusedWhileTheServerIsAtItsStreamQuota() {
        assertThat(streamQuota.tryAcquire()).isTrue();
        assertThat(streamQuota.tryAcquire()).isTrue();

        DuplexEmbeddedChannel visitor = connect();

        assertThat(visitor.isOpen()).isFalse();
        assertThat((Object) sessionChannel.readOutbound()).isNull();
        assertThat(session.openStreamIds()).isEmpty();
        assertThat(metrics.events()).contains("visitorRefused SERVER_STREAM_LIMIT");
        assertThat(streamQuota.inUse()).isEqualTo(2);
    }

    @Test
    void describesTheSessionsFlowAndEachOpenStream() {
        connectAndOpen();

        assertThat(relay.describeFlow())
                .startsWith("SessionFlow[")
                .contains("waiting for the session=[]")
                .contains("\n  stream 1: OPEN StreamFlow[")
                .contains("sendable=65536", "waiting=-", "carried=0", "autoRead=true");

        relay.onClose(Frame.Close.reset(1, ErrorCode.CANCELLED));
        assertThat(relay.describeFlow()).doesNotContain("stream 1");
    }

    @Test
    void aStreamHoldsItsQuotaUntilItEnds() {
        connectAndOpen();
        assertThat(streamQuota.inUse()).isOne();

        relay.onClose(Frame.Close.reset(1, ErrorCode.CANCELLED));

        assertThat(streamQuota.inUse()).isZero();
    }

    @Test
    void visitorBytesBecomeDataWithoutACopyFlushedOncePerReadBatch() {
        DuplexEmbeddedChannel visitor = connectAndOpen();
        ByteBuf bytes = ascii("hello");

        visitor.pipeline().fireChannelRead(bytes);
        assertThat(sessionChannel.outboundMessages()).isEmpty();
        visitor.pipeline().fireChannelReadComplete();

        Frame.Data data = sessionChannel.readOutbound();
        assertThat(data.streamId()).isEqualTo(1);
        assertThat(data.payload()).isSameAs(bytes);
        data.release();
        assertThat(bytes.refCnt()).isZero();
    }

    @Test
    void anEmptyVisitorReadIsReleasedAndNeverSent() {
        DuplexEmbeddedChannel visitor = connectAndOpen();
        ByteBuf empty = Unpooled.buffer(0);

        visitor.pipeline().fireChannelRead(empty);
        visitor.pipeline().fireChannelReadComplete();

        assertThat(empty.refCnt()).isZero();
        assertThat(sessionChannel.outboundMessages()).isEmpty();
    }

    @Test
    void acceptedDataIsWrittenToTheVisitorWithoutACopyAndFlushedWithTheSessionsReadBatch() {
        DuplexEmbeddedChannel visitor = connectAndOpen();
        ByteBuf payload = ascii("world");

        relay.onData(new Frame.Data(1, payload));
        assertThat(visitor.outboundMessages()).isEmpty();
        relay.onReadComplete();

        ByteBuf written = visitor.readOutbound();
        assertThat(written).isSameAs(payload);
        written.release();
        assertThat(payload.refCnt()).isZero();
    }

    @Test
    void creditGoesBackOnlyOnceTheWritesToTheVisitorCompleteAtHalfTheStreamWindow() {
        DuplexEmbeddedChannel visitor = connectAndOpen();

        relay.onData(new Frame.Data(1, Unpooled.wrappedBuffer(new byte[65_536])));
        relay.onData(new Frame.Data(1, Unpooled.wrappedBuffer(new byte[65_536])));
        assertThat(sessionChannel.outboundMessages()).as("nothing written yet").isEmpty();
        relay.onReadComplete();

        assertThat((Frame.WindowUpdate) sessionChannel.readOutbound()).isEqualTo(new Frame.WindowUpdate(1, 131_072));
        assertThat(sessionChannel.outboundMessages()).isEmpty();
        releaseWritten(visitor);
    }

    @Test
    void onlySessionCreditGoesBackOnceTheClientHasClosedItsHalf() {
        DuplexEmbeddedChannel visitor = connectAndOpen();
        for (int i = 0; i < 4; i++) {
            relay.onData(new Frame.Data(1, Unpooled.wrappedBuffer(new byte[65_536])));
        }

        relay.onClose(Frame.Close.half(1));

        assertThat((Frame.WindowUpdate) sessionChannel.readOutbound())
                .as("the client finished the stream, so only session credit goes back")
                .isEqualTo(new Frame.WindowUpdate(0, 262_144));
        assertThat(sessionChannel.outboundMessages()).isEmpty();
        releaseWritten(visitor);
    }

    @Test
    void writesThatFailBecauseTheVisitorWasResetStillReturnSessionCredit() {
        connectAndOpen();
        for (int i = 0; i < 4; i++) {
            relay.onData(new Frame.Data(1, Unpooled.wrappedBuffer(new byte[65_536])));
        }

        relay.onClose(Frame.Close.reset(1, ErrorCode.PEER_RESET));

        assertThat((Frame.WindowUpdate) sessionChannel.readOutbound()).isEqualTo(new Frame.WindowUpdate(0, 262_144));
    }

    @Test
    void dataForAClosedStreamIsDiscardedAndItsSessionCreditReturned() {
        connectAndOpen();
        relay.onClose(Frame.Close.reset(1, ErrorCode.PEER_RESET));

        for (int i = 0; i < 4; i++) {
            relay.onData(new Frame.Data(1, Unpooled.wrappedBuffer(new byte[65_536])));
        }

        assertThat((Frame.WindowUpdate) sessionChannel.readOutbound()).isEqualTo(new Frame.WindowUpdate(0, 262_144));
    }

    @Test
    void noCreditIsSentOnceTheSessionIsClosing() {
        DuplexEmbeddedChannel visitor = connectAndOpen();
        relay.onData(new Frame.Data(1, Unpooled.wrappedBuffer(new byte[131_072 - 65_536])));
        relay.onData(new Frame.Data(1, Unpooled.wrappedBuffer(new byte[65_536])));
        session.onClosing(ErrorCode.SERVER_SHUTTING_DOWN);

        relay.onReadComplete();

        assertThat(sessionChannel.outboundMessages()).isEmpty();
        releaseWritten(visitor);
    }

    @Test
    void aWindowUpdateOnAStreamNeverOpenedIsAProtocolErrorAndOnAClosedOneIsDropped() {
        connectAndOpen();
        relay.onClose(Frame.Close.reset(1, ErrorCode.PEER_RESET));

        relay.onWindowUpdate(new Frame.WindowUpdate(1, 1_000));
        assertThatThrownBy(() -> relay.onWindowUpdate(new Frame.WindowUpdate(3, 1_000)))
                .isInstanceOfSatisfying(
                        ProtocolException.class,
                        error -> assertThat(error.code()).isEqualTo(ErrorCode.PROTOCOL_ERROR));
    }

    @Test
    void aWindowUpdatePastTheLargestCreditIsAFlowControlError() {
        connectAndOpen();

        assertThatThrownBy(() -> relay.onWindowUpdate(new Frame.WindowUpdate(0, Integer.MAX_VALUE)))
                .isInstanceOfSatisfying(
                        ProtocolException.class,
                        error -> assertThat(error.code()).isEqualTo(ErrorCode.FLOW_CONTROL_ERROR));
    }

    @Test
    void aHalfCloseShutsDownOnlyTheVisitorsWriteSide() {
        DuplexEmbeddedChannel visitor = connectAndOpen();

        relay.onClose(Frame.Close.half(1));

        assertThat(visitor.isOutputShutdown()).isTrue();
        assertThat(visitor.isOpen()).isTrue();
        assertThat(session.streamState(1)).contains(StreamState.HALF_CLOSED_REMOTE);
    }

    @Test
    void aHalfCloseAfterTheVisitorsFinClosesTheStreamAndTheConnection() {
        DuplexEmbeddedChannel visitor = connectAndOpen();
        visitor.receiveFin();
        assertThat((Frame.Close) sessionChannel.readOutbound()).isEqualTo(Frame.Close.half(1));

        relay.onClose(Frame.Close.half(1));

        assertThat(visitor.isOutputShutdown()).isTrue();
        assertThat(visitor.isOpen()).isFalse();
        assertThat(session.streamState(1)).isEmpty();
        assertThat(metrics.events()).contains("streamClosed TCP");
    }

    @Test
    void aResetFromTheClientResetsTheVisitorAndDropsWhatWasQueuedForIt() {
        DuplexEmbeddedChannel visitor = connectAndOpen();
        ByteBuf queued = ascii("never delivered");
        relay.onData(new Frame.Data(1, queued));

        relay.onClose(Frame.Close.reset(1, ErrorCode.PEER_RESET));

        assertThat(visitor.isOpen()).isFalse();
        assertThat(queued.refCnt()).isZero();
        assertThat(session.streamState(1)).isEmpty();
        assertThat(metrics.events()).contains("streamReset PEER_RESET REMOTE", "streamClosed TCP");
    }

    @Test
    void dataAfterTheClientsOwnCloseIsReleasedAndResetWithStreamClosed() {
        DuplexEmbeddedChannel visitor = connectAndOpen();
        relay.onClose(Frame.Close.half(1));
        ByteBuf late = ascii("too late");

        relay.onData(new Frame.Data(1, late));

        assertThat(late.refCnt()).isZero();
        assertThat((Frame.Close) sessionChannel.readOutbound())
                .isEqualTo(Frame.Close.reset(1, ErrorCode.STREAM_CLOSED));
        assertThat(visitor.isOpen()).isFalse();
        assertThat(metrics.events()).contains("streamReset STREAM_CLOSED LOCAL");
    }

    @Test
    void framesForAClosedStreamAreReleasedAndDiscarded() {
        connectAndOpen();
        relay.onClose(Frame.Close.reset(1, ErrorCode.PEER_RESET));
        ByteBuf inFlight = ascii("in flight");

        relay.onData(new Frame.Data(1, inFlight));
        relay.onClose(Frame.Close.half(1));

        assertThat(inFlight.refCnt()).isZero();
        assertThat(sessionChannel.outboundMessages()).isEmpty();
    }

    @Test
    void dataOnAStreamNeverOpenedIsReleasedAndIsAProtocolError() {
        connectAndOpen();
        ByteBuf stray = ascii("stray");

        assertThatThrownBy(() -> relay.onData(new Frame.Data(2, stray)))
                .isInstanceOfSatisfying(
                        ProtocolException.class,
                        error -> assertThat(error.code()).isEqualTo(ErrorCode.PROTOCOL_ERROR));
        assertThat(stray.refCnt()).isZero();
    }

    @Test
    void aVisitorsFinBecomesAHalfClose() {
        DuplexEmbeddedChannel visitor = connectAndOpen();

        visitor.receiveFin();

        assertThat((Frame.Close) sessionChannel.readOutbound()).isEqualTo(Frame.Close.half(1));
        assertThat(session.streamState(1)).contains(StreamState.HALF_CLOSED_LOCAL);
        assertThat(visitor.isOpen()).isTrue();
    }

    @Test
    void aVisitorConnectionThatFailsIsResetWithPeerReset() {
        DuplexEmbeddedChannel visitor = connectAndOpen();

        visitor.pipeline().fireExceptionCaught(new IOException("Connection reset by peer"));

        assertThat((Frame.Close) sessionChannel.readOutbound()).isEqualTo(Frame.Close.reset(1, ErrorCode.PEER_RESET));
        assertThat(visitor.isOpen()).isFalse();
        assertThat(sessionChannel.outboundMessages()).isEmpty();
        assertThat(metrics.events()).containsOnlyOnce("streamReset PEER_RESET LOCAL");
    }

    @Test
    void aVisitorConnectionThatClosesMidStreamIsResetWithPeerReset() {
        DuplexEmbeddedChannel visitor = connectAndOpen();

        visitor.close();

        assertThat((Frame.Close) sessionChannel.readOutbound()).isEqualTo(Frame.Close.reset(1, ErrorCode.PEER_RESET));
    }

    @Test
    void cancellingResetsEveryStreamWithCancelledOnce() {
        DuplexEmbeddedChannel visitor = connectAndOpen();

        relay.cancelAll();
        relay.cancelAll();
        sessionChannel.flush();

        assertThat((Frame.Close) sessionChannel.readOutbound()).isEqualTo(Frame.Close.reset(1, ErrorCode.CANCELLED));
        assertThat(sessionChannel.outboundMessages()).isEmpty();
        assertThat(visitor.isOpen()).isFalse();
        assertThat(metrics.events()).containsOnlyOnce("streamReset CANCELLED LOCAL");
    }

    @Test
    void aFinishedStreamsBytesReachTheSessionAndTheMetrics() {
        DuplexEmbeddedChannel visitor = connectAndOpen();
        visitor.pipeline().fireChannelRead(ascii("12345"));
        visitor.pipeline().fireChannelReadComplete();
        ReferenceCountUtil.release(sessionChannel.readOutbound());
        relay.onData(new Frame.Data(1, ascii("123")));
        relay.onReadComplete();
        ReferenceCountUtil.release(visitor.readOutbound());

        relay.onClose(Frame.Close.reset(1, ErrorCode.CANCELLED));

        assertThat(context.streamsServed()).isOne();
        assertThat(context.bytesIn()).isEqualTo(5);
        assertThat(context.bytesOut()).isEqualTo(3);
        assertThat(metrics.events()).contains("tunnelBytes TCP INBOUND 5", "tunnelBytes TCP OUTBOUND 3");
    }

    private DuplexEmbeddedChannel connect() {
        DuplexEmbeddedChannel visitor = new DuplexEmbeddedChannel(VISITOR, new VisitorHandler(relay, TUNNEL));
        visitors.add(visitor);
        return visitor;
    }

    /** Connects a visitor and takes its {@code OPEN} off the session channel. */
    private DuplexEmbeddedChannel connectAndOpen() {
        DuplexEmbeddedChannel visitor = connect();
        assertThat((Object) sessionChannel.readOutbound()).isInstanceOf(Frame.Open.class);
        return visitor;
    }

    private static void releaseWritten(EmbeddedChannel visitor) {
        for (Object written = visitor.readOutbound(); written != null; written = visitor.readOutbound()) {
            ReferenceCountUtil.release(written);
        }
    }

    private static ByteBuf ascii(String text) {
        return Unpooled.copiedBuffer(text, StandardCharsets.US_ASCII);
    }
}
