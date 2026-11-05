package io.jburrow.server.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import io.jburrow.client.tunnel.application.port.TunnelEvents;
import io.jburrow.client.tunnel.domain.ExchangeSummary;
import io.jburrow.client.tunnel.domain.TunnelClosed;
import io.jburrow.protocol.control.RegisteredTunnel;
import io.jburrow.protocol.wire.ErrorCode;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * What a client reports about its sessions, kept for a test to wait on. Every report also goes on one
 * {@linkplain #timeline() timeline}, in the order the client made them, with the time it arrived, so a test can check
 * what came before what, and how far apart a client's attempts were.
 */
final class RecordingEvents implements TunnelEvents {

    /** A stream as the client saw it end. */
    record StreamClosed(int streamId, long bytesIn, long bytesOut, Optional<ErrorCode> reset) {}

    /** A session ended, or an attempt failed, and the client said when it would try next. */
    record Disconnected(TunnelClosed reason, Duration retryIn, long atNanos) {}

    /** A later session was established. */
    record Reconnected(String sessionId, List<RegisteredTunnel> tunnels, boolean sameUrls, long atNanos) {}

    /** One report, as {@link System#nanoTime} saw it arrive. */
    record Entry(String kind, long atNanos) {}

    private final BlockingQueue<String> opened = new LinkedBlockingQueue<>();
    private final BlockingQueue<StreamClosed> closedStreams = new LinkedBlockingQueue<>();
    private final BlockingQueue<ExchangeSummary> exchanges = new LinkedBlockingQueue<>();
    private final List<Disconnected> disconnects = new CopyOnWriteArrayList<>();
    private final BlockingQueue<Reconnected> reconnects = new LinkedBlockingQueue<>();
    private final List<Reconnected> allReconnects = new CopyOnWriteArrayList<>();
    private final List<Entry> timeline = new CopyOnWriteArrayList<>();
    private final CompletableFuture<TunnelClosed> closed = new CompletableFuture<>();

    @Override
    public void onStreamOpened(int streamId, String tunnelRef, String remoteAddress, int remotePort) {
        note("streamOpened");
        opened.add(remoteAddress + ":" + remotePort);
    }

    @Override
    public void onStreamClosed(
            int streamId, long bytesIn, long bytesOut, long durationMillis, Optional<ErrorCode> reset) {
        note("streamClosed");
        closedStreams.add(new StreamClosed(streamId, bytesIn, bytesOut, reset));
    }

    @Override
    public void onDisconnected(TunnelClosed reason, Duration retryIn) {
        disconnects.add(new Disconnected(reason, retryIn, note("disconnected")));
    }

    @Override
    public void onReconnected(String sessionId, List<RegisteredTunnel> tunnels, boolean sameUrls) {
        Reconnected reconnected = new Reconnected(sessionId, List.copyOf(tunnels), sameUrls, note("reconnected"));
        allReconnects.add(reconnected);
        reconnects.add(reconnected);
    }

    @Override
    public void onClosed(TunnelClosed reason) {
        note("closed");
        closed.complete(reason);
    }

    /** The next later session the client established, failing the test after {@code timeout}. */
    Reconnected awaitReconnected(Duration timeout) throws InterruptedException {
        Reconnected reconnected = reconnects.poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
        assertThat(reconnected).as("a reconnect within %s", timeout).isNotNull();
        return reconnected;
    }

    /** Every session end and failed attempt reported so far, in order. */
    List<Disconnected> disconnects() {
        return List.copyOf(disconnects);
    }

    /** Every later session reported so far, in order. */
    List<Reconnected> reconnects() {
        return List.copyOf(allReconnects);
    }

    /** Every report so far, in order. */
    List<Entry> timeline() {
        return List.copyOf(timeline);
    }

    /** Whether the client has stopped for good. */
    boolean stopped() {
        return closed.isDone();
    }

    private long note(String kind) {
        long now = System.nanoTime();
        timeline.add(new Entry(kind, now));
        return now;
    }

    @Override
    public void onExchangeCompleted(ExchangeSummary exchange) {
        note("exchangeCompleted");
        exchanges.add(exchange);
    }

    /** The next exchange the client reported finished. */
    ExchangeSummary awaitExchange(Duration timeout) throws InterruptedException {
        ExchangeSummary exchange = exchanges.poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
        assertThat(exchange).as("an exchange finished within %s", timeout).isNotNull();
        return exchange;
    }

    /** The visitor address of the next stream the client opened. */
    String awaitStreamOpened(Duration timeout) throws InterruptedException {
        String visitor = opened.poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
        assertThat(visitor).as("a stream opened within %s", timeout).isNotNull();
        return visitor;
    }

    /** The next stream the client reported closed. */
    StreamClosed awaitStreamClosed(Duration timeout) throws InterruptedException {
        StreamClosed closed = closedStreams.poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
        assertThat(closed).as("a stream closed within %s", timeout).isNotNull();
        return closed;
    }
}
