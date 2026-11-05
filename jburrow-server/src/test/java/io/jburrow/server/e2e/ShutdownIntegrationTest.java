package io.jburrow.server.e2e;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import io.jburrow.client.tunnel.domain.TunnelClosed;
import io.jburrow.protocol.adapter.netty.Frame;
import io.jburrow.protocol.control.GoAway;
import io.jburrow.protocol.control.Register;
import io.jburrow.protocol.control.RegisteredTunnel;
import io.jburrow.protocol.testsupport.ScriptedPeer;
import io.jburrow.protocol.wire.Capability;
import io.jburrow.protocol.wire.ErrorCode;
import io.jburrow.server.e2e.support.HttpVisitor;
import io.jburrow.server.e2e.support.ServerContexts;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.context.TestPropertySource;

/**
 * Closing the application drains it ({@code docs/phase-4-staying-connected/ARCHITECTURE.md} §The drain), end to end: a
 * client with {@code resume} is sent {@code GOAWAY}, a download in flight through its tunnel finishes, and the drain
 * ends when it does, in about a second rather than the 5 seconds it allows; then every session ends with
 * {@code SERVER_SHUTTING_DOWN} and every port is free. A client without {@code resume}, as a phase 3 CLI is, gets no
 * {@code GOAWAY}, only the {@code ERROR} it always got.
 */
class ShutdownIntegrationTest {

    @Nested
    @EnabledIf("io.netty.channel.epoll.Epoll#isAvailable")
    @TestPropertySource(properties = {"jburrow.tcp.port-range=31300-31309", "jburrow.edge.transport=epoll"})
    class OnEpoll extends Scenario {
        OnEpoll() {
            super(new ServerContexts.Ports(31_350), ON_EPOLL);
        }
    }

    @Nested
    @TestPropertySource(properties = {"jburrow.tcp.port-range=31310-31319", "jburrow.edge.transport=nio"})
    class OnNio extends Scenario {
        OnNio() {
            super(new ServerContexts.Ports(31_370), ON_NIO);
        }
    }

    @Timeout(value = 1, unit = TimeUnit.MINUTES, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    @ExtendWith(OutputCaptureExtension.class)
    abstract static class Scenario extends TunnelTestSupport {

        private static final Pattern DRAIN =
                Pattern.compile("drain: sessions=(\\d+) streams=(\\d+) cut=(\\d+) ms=(\\d+)");
        private static final int EVENTS = 5;
        private static final Duration EVENT_INTERVAL = Duration.ofMillis(250);
        /** {@code jburrow.drain.timeout}'s default, which the test profile keeps. */
        private static final Duration DRAIN_TIMEOUT = Duration.ofSeconds(5);
        /** Under the drain's timeout by more than any scheduling hiccup, so a drain that waited it out fails. */
        private static final Duration DRAIN_ENDS_WITHIN = Duration.ofSeconds(3);

        private final ServerContexts.Ports ports;
        private final String transport;

        Scenario(ServerContexts.Ports ports, String transport) {
            this.ports = ports;
            this.transport = transport;
        }

        @Test
        void aDownloadInFlightFinishesTheDrainEndsWithItAndEverySessionEndsAfter(CapturedOutput output)
                throws Exception {
            ServerContexts servers = serverContexts(ports, transport);
            servers.start();
            MeterRegistry meters = servers.running().getBean(MeterRegistry.class);
            LocalHttpService web = closeAfterTest(LocalHttpService.start());
            Tunnel alice = openTunnel(httpClient(ALICE_TOKEN, web.port()).server(servers.directConnectUri()));
            ScriptedPeer resuming = helloed(servers.directConnectUri(), List.of(Capability.HTTP, Capability.RESUME));
            resuming.send(new Frame.Register(new Register(ALICE_TOKEN, List.of(httpRequest(null)))));
            resuming.expect(Frame.Registered.class, TIMEOUT);
            ScriptedPeer phase3 = helloed(servers.directConnectUri(), List.of(Capability.HTTP));
            phase3.send(new Frame.Register(new Register(BOB_TOKEN, List.of(tcpRequest(null)))));
            RegisteredTunnel phase3Tunnel = phase3.expect(Frame.Registered.class, TIMEOUT)
                    .message()
                    .tunnels()
                    .getFirst();

            HttpVisitor visitor = closeAfterTest(HttpVisitor.connect(ports.edge(), TIMEOUT));
            visitor.request("GET", alice.host(), "/sse?count=" + EVENTS + "&interval=" + EVENT_INTERVAL.toMillis());
            HttpVisitor.Head head = visitor.head();
            assertThat(head.status()).isEqualTo(200);
            CompletableFuture<Void> stopped = async(() -> {
                servers.stop();
                return null;
            });

            GoAway goAway = resuming.expect(Frame.GoAway.class, TIMEOUT).message();
            assertThat(goAway.drainMs()).isEqualTo(DRAIN_TIMEOUT.toMillis());
            assertThat(goAway.reconnectAfterMs()).isBetween(0L, 2_000L);
            String events = new String(visitor.body(head).readAllBytes(), UTF_8);
            assertThat(events.lines().filter(line -> line.startsWith("data: event")))
                    .hasSize(EVENTS);

            phase3.expectError(ErrorCode.SERVER_SHUTTING_DOWN, TIMEOUT);
            resuming.expectError(ErrorCode.SERVER_SHUTTING_DOWN, TIMEOUT);
            TunnelClosed closed = alice.awaitClosed(TIMEOUT);
            assertThat(closed.reason()).isEqualTo(TunnelClosed.Reason.SERVER_ERROR);
            assertThat(closed.code()).contains(ErrorCode.SERVER_SHUTTING_DOWN);
            stopped.get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);

            Matcher drain = DRAIN.matcher(output.getAll());
            assertThat(drain.find()).as("the drain's line").isTrue();
            assertThat(drain.group(1)).as("sessions").isEqualTo("3");
            assertThat(drain.group(2)).as("streams open at GOAWAY").isEqualTo("1");
            assertThat(drain.group(3)).as("streams cut").isEqualTo("0");
            assertThat(Duration.ofMillis(Long.parseLong(drain.group(4))))
                    .isGreaterThan(EVENT_INTERVAL)
                    .isLessThan(DRAIN_ENDS_WITHIN);
            assertThat(meters.get("jburrow.goaway.sent").counter().count())
                    .as("GOAWAYs: the client and the scripted peer with resume")
                    .isEqualTo(2);
            assertThat(meters.get("jburrow.drain.streams.cut").counter().count())
                    .isZero();
            assertThat(bindable(phase3Tunnel.publicPort())).isTrue();
        }
    }
}
