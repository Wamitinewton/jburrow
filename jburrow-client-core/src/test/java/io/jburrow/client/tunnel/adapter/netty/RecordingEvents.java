package io.jburrow.client.tunnel.adapter.netty;

import io.jburrow.client.tunnel.application.port.TunnelEvents;
import io.jburrow.client.tunnel.domain.ExchangeSummary;
import io.jburrow.client.tunnel.domain.TunnelClosed;
import io.jburrow.protocol.control.RegisteredTunnel;
import io.jburrow.protocol.wire.ErrorCode;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

/** Records every event as one line of text, and checks they all arrive on the client's I/O thread. */
final class RecordingEvents implements TunnelEvents {

    private final List<String> events = new CopyOnWriteArrayList<>();
    private final List<String> wrongThreads = new CopyOnWriteArrayList<>();

    @Override
    public void onEstablished(String sessionId, List<RegisteredTunnel> tunnels) {
        record("established " + sessionId + " " + tunnels.getFirst().url());
    }

    @Override
    public void onStreamOpened(int streamId, String tunnelRef, String remoteAddress, int remotePort) {
        record("opened " + streamId + " " + tunnelRef + " " + remoteAddress + ":" + remotePort);
    }

    @Override
    public void onStreamClosed(
            int streamId, long bytesIn, long bytesOut, long durationMillis, Optional<ErrorCode> reset) {
        if (durationMillis < 0) {
            throw new AssertionError("negative duration");
        }
        record("stream-closed " + streamId + " in=" + bytesIn + " out=" + bytesOut + " reset="
                + reset.map(ErrorCode::code).orElse("-"));
    }

    @Override
    public void onExchangeCompleted(ExchangeSummary exchange) {
        record("exchange " + exchange.streamId() + " " + exchange.method() + " " + exchange.target() + " status="
                + (exchange.status().isPresent() ? exchange.status().getAsInt() : "-") + " in="
                + exchange.requestBytes() + " out=" + exchange.responseBytes() + " reset="
                + exchange.reset().map(ErrorCode::code).orElse("-"));
    }

    @Override
    public void onClosed(TunnelClosed closed) {
        record("closed " + closed.reason() + " "
                + closed.code().map(ErrorCode::code).orElse("-"));
    }

    List<String> events() {
        if (!wrongThreads.isEmpty()) {
            throw new AssertionError("events arrived off the client's I/O thread: " + wrongThreads);
        }
        return List.copyOf(events);
    }

    /** Waits for an event starting with {@code prefix} and returns it. */
    String await(String prefix, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            for (String event : events()) {
                if (event.startsWith(prefix)) {
                    return event;
                }
            }
            Thread.sleep(10);
        }
        throw new AssertionError("No event starting with \"" + prefix + "\" within " + timeout + ": " + events);
    }

    private void record(String event) {
        String thread = Thread.currentThread().getName();
        if (!thread.equals(NettyTunnelClient.IO_THREAD_NAME)) {
            wrongThreads.add(thread + ": " + event);
        }
        events.add(event);
    }
}
