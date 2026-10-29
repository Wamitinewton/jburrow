package io.jburrow.server.core.tunnel.adapter.netty.control;

import static org.assertj.core.api.Assertions.assertThat;

import io.jburrow.protocol.adapter.netty.Frame;
import io.jburrow.protocol.control.ClientInfo;
import io.jburrow.protocol.control.Hello;
import io.jburrow.protocol.control.Windows;
import io.jburrow.protocol.wire.ErrorCode;
import io.jburrow.server.core.tunnel.application.RecordingTunnelMetrics;
import io.jburrow.server.core.tunnel.application.SessionAdmission;
import io.jburrow.server.core.tunnel.domain.Session;
import io.jburrow.server.core.tunnel.domain.SessionId;
import io.jburrow.server.core.tunnel.domain.SessionState;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import java.net.InetAddress;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LivenessHandlerTest {

    private static final Duration PING_INTERVAL = Duration.ofSeconds(1);
    private static final Duration IDLE_TIMEOUT = Duration.ofSeconds(3);

    private final AtomicLong now = new AtomicLong(1_000_000_000L);
    private final RecordingTunnelMetrics metrics = new RecordingTunnelMetrics();
    private EmbeddedChannel channel;
    private Session session;
    private LivenessHandler liveness;

    @BeforeEach
    void setUp() {
        channel = new EmbeddedChannel();
        channel.freezeTime();
        session = new Session(SessionId.random(), 1, new Windows(262_144, 524_288));
        SessionCloser closer = new SessionCloser(metrics);
        SessionContext context = new SessionContext(
                session,
                channel,
                new SessionAdmission(1, 1, 1).tryBeginHandshake().orElseThrow(),
                InetAddress.getLoopbackAddress(),
                now.get(),
                closer);
        liveness = new LivenessHandler(context, closer, PING_INTERVAL, IDLE_TIMEOUT, now::get);
        context.liveness(liveness);
        channel.pipeline().addLast(liveness);
    }

    @AfterEach
    void tearDown() {
        channel.finishAndReleaseAll();
    }

    @Test
    void framesPassUntouchedUntilTheClockStarts() {
        channel.writeInbound(new Frame.Ping(7));

        assertThat((Object) channel.readInbound()).isEqualTo(new Frame.Ping(7));
        advance(IDLE_TIMEOUT.multipliedBy(2));
        assertThat((Object) channel.readOutbound()).isNull();
    }

    @Test
    void pingsAfterTheIntervalAndEndsTheSessionAfterTheIdleTimeout() {
        start();

        advance(PING_INTERVAL.minusMillis(1));
        assertThat((Object) channel.readOutbound()).isNull();
        advance(Duration.ofMillis(1));
        assertThat((Object) channel.readOutbound()).isEqualTo(new Frame.Ping(1));

        advance(IDLE_TIMEOUT.minus(PING_INTERVAL).minusMillis(1));
        assertThat((Object) channel.readOutbound()).isNull();
        advance(Duration.ofMillis(1));
        assertHeartbeatTimeout();
    }

    @Test
    void anyFrameReceivedPushesThePingBack() {
        start();

        advance(Duration.ofMillis(900));
        channel.writeInbound(Frame.Close.half(1));
        assertThat((Object) channel.readInbound()).isEqualTo(Frame.Close.half(1));

        advance(Duration.ofMillis(999));
        assertThat((Object) channel.readOutbound()).isNull();
        advance(Duration.ofMillis(1));
        assertThat((Object) channel.readOutbound()).isEqualTo(new Frame.Ping(1));
    }

    @Test
    void theAnswerToAPingBringsTheNextOneForwardAndIsNotForwarded() {
        start();
        advance(PING_INTERVAL);
        assertThat((Object) channel.readOutbound()).isEqualTo(new Frame.Ping(1));

        advance(Duration.ofMillis(500));
        channel.writeInbound(new Frame.Pong(1));
        assertThat((Object) channel.readInbound()).isNull();

        advance(PING_INTERVAL.minusMillis(1));
        assertThat((Object) channel.readOutbound()).isNull();
        advance(Duration.ofMillis(1));
        assertThat((Object) channel.readOutbound()).isEqualTo(new Frame.Ping(2));
    }

    @Test
    void aStrayPongIsDroppedButStillCountsAsProofOfLife() {
        start();
        advance(Duration.ofMillis(900));

        channel.writeInbound(new Frame.Pong(42));

        assertThat((Object) channel.readInbound()).isNull();
        advance(Duration.ofMillis(999));
        assertThat((Object) channel.readOutbound()).isNull();
        advance(Duration.ofMillis(1));
        assertThat((Object) channel.readOutbound()).isEqualTo(new Frame.Ping(1));
    }

    @Test
    void answersEachPingWithItsPongWithoutForwardingIt() {
        start();

        channel.writeInbound(new Frame.Ping(5), new Frame.Ping(6));

        assertThat((Object) channel.readInbound()).isNull();
        assertThat(List.of(channel.readOutbound(), channel.readOutbound()))
                .containsExactly(new Frame.Pong(5), new Frame.Pong(6));
    }

    @Test
    void aBurstOfPingsBeyondTheLimitEndsTheSession() {
        start();

        for (int i = 0; i < 6; i++) {
            channel.writeInbound(new Frame.Ping(i));
        }

        for (int i = 0; i < 5; i++) {
            assertThat((Object) channel.readOutbound()).isEqualTo(new Frame.Pong(i));
        }
        assertError(ErrorCode.PING_RATE_EXCEEDED);
        assertThat(metrics.events()).contains("protocolError PING_RATE_EXCEEDED LOCAL");
    }

    @Test
    void pingsAtTheSustainedRateAreAnswered() {
        start();

        for (int i = 0; i < 20; i++) {
            channel.writeInbound(new Frame.Ping(i));
            assertThat((Object) channel.readOutbound()).isEqualTo(new Frame.Pong(i));
            advance(Duration.ofMillis(900));
        }
        assertThat(session.state()).isNotEqualTo(SessionState.CLOSING);
    }

    @Test
    void nothingIsScheduledOnceTheChannelCloses() {
        start();

        channel.close();
        advance(IDLE_TIMEOUT.multipliedBy(2));

        assertThat((Object) channel.readOutbound()).isNull();
    }

    private void start() {
        session.onHello(new Hello(new ClientInfo("test", "1", null, null), List.of(), new Windows(262_144, 1_048_576)));
        liveness.start();
    }

    private void advance(Duration duration) {
        now.addAndGet(duration.toNanos());
        channel.advanceTimeBy(duration.toNanos(), TimeUnit.NANOSECONDS);
        channel.runScheduledPendingTasks();
    }

    private void assertHeartbeatTimeout() {
        assertError(ErrorCode.HEARTBEAT_TIMEOUT);
        assertThat(session.closeReason()).contains(ErrorCode.HEARTBEAT_TIMEOUT);
    }

    private void assertError(ErrorCode code) {
        Frame.Error error = channel.readOutbound();
        assertThat(error.body().code()).isEqualTo(code);
        CloseWebSocketFrame close = channel.readOutbound();
        try {
            assertThat(close.statusCode()).isEqualTo(code.closeCode().orElseThrow());
        } finally {
            close.release();
        }
    }
}
