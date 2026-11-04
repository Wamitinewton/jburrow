package io.jburrow.client.tunnel.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.code_intelligence.jazzer.api.FuzzedDataProvider;
import com.code_intelligence.jazzer.junit.FuzzTest;
import io.jburrow.protocol.control.GoAway;
import io.jburrow.protocol.control.Open;
import io.jburrow.protocol.control.Registered;
import io.jburrow.protocol.control.RegisteredTunnel;
import io.jburrow.protocol.control.RemoteAddress;
import io.jburrow.protocol.control.ResumeToken;
import io.jburrow.protocol.control.ServerHello;
import io.jburrow.protocol.control.ServerInfo;
import io.jburrow.protocol.control.SessionLimits;
import io.jburrow.protocol.control.TunnelKind;
import io.jburrow.protocol.control.Windows;
import io.jburrow.protocol.flow.DataOutcome;
import io.jburrow.protocol.stream.StreamEvent;
import io.jburrow.protocol.wire.Capability;
import io.jburrow.protocol.wire.FrameType;
import io.jburrow.protocol.wire.ProtocolException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Drives one {@link ClientSession} through an arbitrary sequence of frames from the server around {@code GOAWAY} and
 * {@code REGISTER}, handled as {@code ControlFrameHandler} handles them: checked against the session's state, then
 * acted on, and a {@link ProtocolException} closes the session.
 *
 * <p>The properties: a frame from the server never throws anything but a {@link ProtocolException}; the session only
 * moves forward through its states, and never leaves {@code CLOSING}; it accepts {@code GOAWAY} at most once, only
 * when established and only with {@code resume}; it accepts no {@code OPEN} once draining; and it holds a resume token
 * exactly when it was established with {@code resume}.
 */
class SessionResumeFuzzTest {

    private static final UUID SESSION_ID = UUID.fromString("0b8e5c1e-7c55-4c46-9a3e-2f1d0c9e4b7a");
    private static final UUID TUNNEL_ID = UUID.fromString("5d0f4f0e-31a6-4a39-8d51-0c0f7f1f6c23");
    private static final Windows WINDOWS = new Windows(65_536, 131_072);
    private static final SessionLimits LIMITS = new SessionLimits(65_536, 1, 100, 15_000, 45_000);
    private static final ResumeToken PREVIOUS = new ResumeToken("jbr_AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8");
    private static final ResumeToken ISSUED = new ResumeToken("jbr_ICEiIyQlJicoKSorLC0uLzAxMjM0NTY3ODk6Ozw9Pj8");
    private static final RemoteAddress VISITOR = new RemoteAddress("203.0.113.9", 51_234);
    private static final int MAX_STREAM_ID = 64;

    private enum Event {
        HELLO_WITH_RESUME,
        HELLO_WITHOUT_RESUME,
        REPLY_WITH_TOKEN,
        REPLY_WITHOUT_TOKEN,
        GOAWAY,
        OPEN,
        DATA,
        WINDOW_UPDATE,
        CLOSE,
        RESET,
        PING,
        ERROR
    }

    private static final Event[] EVENTS = Event.values();

    @FuzzTest(maxDuration = "1m")
    void sessions(FuzzedDataProvider data) {
        ClientSession session = new ClientSession(
                List.of(TunnelSpec.tcp("default", LocalTarget.of("localhost", 22))),
                WINDOWS,
                Set.of(Capability.RESUME),
                data.consumeBoolean()
                        ? Resumption.after(List.of(), List.of(), Optional.of(PREVIOUS))
                        : Resumption.none());
        session.onUpgraded();
        Map<Integer, Integer> held = new HashMap<>();
        int goAways = 0;
        int nextStreamId = 1;

        while (data.remainingBytes() > 0 && !session.isClosing()) {
            Event event = data.pickValue(EVENTS);
            ClientSessionState before = session.state();
            try {
                switch (event) {
                    case HELLO_WITH_RESUME -> {
                        session.checkInbound(FrameType.HELLO);
                        session.onServerHello(hello(List.of("resume")));
                    }
                    case HELLO_WITHOUT_RESUME -> {
                        session.checkInbound(FrameType.HELLO);
                        session.onServerHello(hello(List.of()));
                    }
                    case REPLY_WITH_TOKEN -> {
                        session.checkInbound(FrameType.REGISTER);
                        session.onRegistered(new Registered(List.of(tunnel()), ISSUED));
                    }
                    case REPLY_WITHOUT_TOKEN -> {
                        session.checkInbound(FrameType.REGISTER);
                        session.onRegistered(new Registered(List.of(tunnel())));
                    }
                    case GOAWAY -> {
                        session.checkInbound(FrameType.GOAWAY);
                        session.onGoAway(new GoAway(data.consumeLong(0, 60_000), data.consumeLong(0, 300_000)));
                        goAways++;
                        assertThat(before).isEqualTo(ClientSessionState.ESTABLISHED);
                        assertThat(session.resumeEnabled()).isTrue();
                    }
                    case OPEN -> {
                        int streamId = data.consumeBoolean() ? nextStreamId : data.consumeInt(1, MAX_STREAM_ID);
                        session.checkInbound(FrameType.OPEN);
                        session.onOpen(streamId, new Open(TUNNEL_ID, TunnelKind.TCP, VISITOR));
                        assertThat(before).isEqualTo(ClientSessionState.ESTABLISHED);
                        nextStreamId = Math.max(nextStreamId, streamId + 1);
                    }
                    case DATA -> {
                        int streamId = data.consumeInt(1, MAX_STREAM_ID);
                        int bytes = data.consumeInt(1, 65_536);
                        session.checkInbound(FrameType.DATA);
                        if (session.onDataReceived(streamId, bytes) == DataOutcome.DELIVER) {
                            held.merge(streamId, bytes, Integer::sum);
                        }
                        Integer release = held.remove(streamId);
                        if (release != null && data.consumeBoolean()) {
                            session.onReleased(streamId, release);
                        } else if (release != null) {
                            held.put(streamId, release);
                        }
                    }
                    case WINDOW_UPDATE -> {
                        session.checkInbound(FrameType.WINDOW_UPDATE);
                        session.onWindowUpdate(
                                data.consumeInt(0, MAX_STREAM_ID), data.consumeInt(1, Integer.MAX_VALUE));
                    }
                    case CLOSE -> {
                        session.checkInbound(FrameType.CLOSE);
                        session.onStreamFrame(data.consumeInt(1, MAX_STREAM_ID), StreamEvent.CLOSE_RECEIVED);
                    }
                    case RESET -> {
                        session.checkInbound(FrameType.CLOSE);
                        session.onStreamFrame(data.consumeInt(1, MAX_STREAM_ID), StreamEvent.RST_RECEIVED);
                    }
                    case PING -> session.checkInbound(FrameType.PING);
                    case ERROR -> {
                        session.checkInbound(FrameType.ERROR);
                        session.onClosing();
                    }
                }
            } catch (ProtocolException e) {
                session.onClosing();
            }

            assertMovesForward(before, session.state());
            assertThat(goAways).isLessThanOrEqualTo(1);
            assertThat(session.resumeToken().isPresent())
                    .as("a token exactly when established with resume")
                    .isEqualTo(reachedRegistered(session) && session.resumeEnabled());
        }
    }

    /** Whether the session got as far as the {@code REGISTER} reply, whatever happened after. */
    private static boolean reachedRegistered(ClientSession session) {
        return !session.tunnels().isEmpty();
    }

    private static void assertMovesForward(ClientSessionState before, ClientSessionState after) {
        assertThat(after.ordinal()).as("%s after %s", after, before).isGreaterThanOrEqualTo(before.ordinal());
        if (before == ClientSessionState.DRAINING) {
            assertThat(after).isIn(ClientSessionState.DRAINING, ClientSessionState.CLOSING);
        }
    }

    private static ServerHello hello(List<String> capabilities) {
        return new ServerHello(SESSION_ID, new ServerInfo("jburrow-server", "0.4.0"), capabilities, LIMITS, WINDOWS);
    }

    private static RegisteredTunnel tunnel() {
        return RegisteredTunnel.of("default", TUNNEL_ID, TunnelKind.TCP, "tcp.example.dev", 20_417);
    }
}
