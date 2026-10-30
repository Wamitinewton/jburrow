package io.jburrow.server.core.tunnel.adapter.netty.edge;

import static io.jburrow.server.core.tunnel.adapter.netty.edge.EmbeddedEdge.CONNECT_HOST;
import static io.jburrow.server.core.tunnel.adapter.netty.edge.EmbeddedEdge.KEEP_ALIVE_TIMEOUT;
import static io.jburrow.server.core.tunnel.adapter.netty.edge.EmbeddedEdge.REQUEST_TIMEOUT;
import static io.jburrow.server.core.tunnel.adapter.netty.edge.EmbeddedEdge.get;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class RequestDeadlineTest {

    private static final Duration MARGIN = Duration.ofSeconds(1);

    private final EmbeddedEdge edge = new EmbeddedEdge();

    @AfterEach
    void close() {
        edge.close();
    }

    @Test
    void aNewConnectionHasTheRequestTimeoutForItsFirstHead() {
        edge.send("GET / HTTP/1.1\r\nHost: quiet");
        advance(REQUEST_TIMEOUT.minus(MARGIN));
        assertThat(edge.channel.isOpen()).isTrue();

        advance(MARGIN.multipliedBy(2));

        assertThat(edge.channel.isOpen()).isFalse();
    }

    @Test
    void noDeadlineRunsWhileAnExchangeIsInProgress() {
        edge.send("POST / HTTP/1.1\r\nHost: quiet-otter.example.dev\r\nContent-Length: 10\r\n\r\n");

        advance(KEEP_ALIVE_TIMEOUT.multipliedBy(10));

        assertThat(edge.channel.isOpen()).isTrue();
    }

    @Test
    void anIdleConnectionBetweenExchangesHasTheKeepAliveTimeout() {
        edge.send(get("quiet-otter.example.dev", "/"));
        advance(REQUEST_TIMEOUT.multipliedBy(2));
        edge.exchanges.end();

        advance(KEEP_ALIVE_TIMEOUT.minus(MARGIN));
        assertThat(edge.channel.isOpen())
                .as("the request timeout no longer applies")
                .isTrue();

        advance(MARGIN.multipliedBy(2));

        assertThat(edge.channel.isOpen()).isFalse();
    }

    @Test
    void theKeepAliveTimeoutRestartsAfterEachExchange() {
        edge.send(get("nowhere.test", "/"));
        advance(KEEP_ALIVE_TIMEOUT.minus(MARGIN));
        edge.send(get("nowhere.test", "/"));

        advance(KEEP_ALIVE_TIMEOUT.minus(MARGIN));

        assertThat(edge.channel.isOpen()).isTrue();
    }

    @Test
    void aConnectRequestAfterExchangesHasTheRequestTimeoutUntilItsUpgrade() {
        edge.send(get("nowhere.test", "/"));
        edge.send("GET /v1/connect HTTP/1.1\r\nHost: " + CONNECT_HOST + "\r\nContent-Length: 10\r\n\r\n");
        advance(REQUEST_TIMEOUT.minus(MARGIN));
        assertThat(edge.channel.isOpen()).isTrue();

        advance(MARGIN.multipliedBy(2));

        assertThat(edge.channel.isOpen()).isFalse();
    }

    private void advance(Duration duration) {
        edge.channel.advanceTimeBy(duration.toNanos(), TimeUnit.NANOSECONDS);
        edge.channel.runScheduledPendingTasks();
    }
}
