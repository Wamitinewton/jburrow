package io.jburrow.server.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import io.jburrow.client.tunnel.application.port.TunnelEvents;
import io.jburrow.client.tunnel.domain.ExchangeSummary;
import io.jburrow.protocol.wire.ErrorCode;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/** What a client reports about its session, kept for a test to wait on. */
final class RecordingEvents implements TunnelEvents {

    /** A stream as the client saw it end. */
    record StreamClosed(int streamId, long bytesIn, long bytesOut, Optional<ErrorCode> reset) {}

    private final BlockingQueue<String> opened = new LinkedBlockingQueue<>();
    private final BlockingQueue<StreamClosed> closedStreams = new LinkedBlockingQueue<>();
    private final BlockingQueue<ExchangeSummary> exchanges = new LinkedBlockingQueue<>();

    @Override
    public void onStreamOpened(int streamId, String tunnelRef, String remoteAddress, int remotePort) {
        opened.add(remoteAddress + ":" + remotePort);
    }

    @Override
    public void onStreamClosed(
            int streamId, long bytesIn, long bytesOut, long durationMillis, Optional<ErrorCode> reset) {
        closedStreams.add(new StreamClosed(streamId, bytesIn, bytesOut, reset));
    }

    @Override
    public void onExchangeCompleted(ExchangeSummary exchange) {
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
