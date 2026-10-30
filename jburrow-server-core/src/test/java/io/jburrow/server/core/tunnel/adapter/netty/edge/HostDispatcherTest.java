package io.jburrow.server.core.tunnel.adapter.netty.edge;

import static io.jburrow.server.core.tunnel.adapter.netty.edge.EmbeddedEdge.CONNECT_HOST;
import static io.jburrow.server.core.tunnel.adapter.netty.edge.EmbeddedEdge.get;
import static io.jburrow.server.core.tunnel.adapter.netty.edge.EmbeddedEdge.upgrade;
import static org.assertj.core.api.Assertions.assertThat;

import io.jburrow.protocol.control.SubdomainName;
import io.jburrow.server.core.tunnel.adapter.netty.http.ExchangeRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class HostDispatcherTest {

    private final EmbeddedEdge edge = new EmbeddedEdge();

    @AfterEach
    void close() {
        edge.close();
    }

    @ParameterizedTest
    @ValueSource(strings = {CONNECT_HOST, "CONNECT.Example.DEV", CONNECT_HOST + ":443", CONNECT_HOST + ":7080"})
    void theConnectHostUpgradesThroughPhaseOnesHandlers(String host) {
        edge.send(upgrade(host));

        assertThat(edge.written()).startsWith("HTTP/1.1 101 Switching Protocols");
        assertThat(edge.upgrades).containsExactly("jburrow.v1-draft.2");
        assertThat(edge.channel.pipeline().get(EdgeChannelInitializer.HOST_DISPATCHER))
                .isNull();
        assertThat(edge.channel.pipeline().get(EdgeChannelInitializer.REQUEST_DEADLINE))
                .isNull();
        assertThat(edge.channel.config().isAutoRead()).isTrue();
        assertThat(edge.exchanges.requests).isEmpty();
    }

    @Test
    void aConnectRequestPhaseOneRefusesGetsItsJsonAndTheConnectionCloses() {
        edge.send(get(CONNECT_HOST, "/"));

        String response = edge.written();
        assertThat(response).startsWith("HTTP/1.1 404 Not Found").contains("application/json", "\"NOT_FOUND\"");
        assertThat(response).doesNotContain("jburrow-error");
        assertThat(edge.channel.isOpen()).isFalse();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "quiet-otter-4f2a.example.dev",
                "Quiet-Otter-4F2A.Example.Dev",
                "quiet-otter-4f2a.example.dev:443"
            })
    void aTunnelHostGoesToTheExchangeHandlerWithItsName(String host) {
        edge.send(get(host, "/hello?x=1"));

        assertThat(edge.exchanges.requests).hasSize(1);
        ExchangeRequest request = edge.exchanges.requests.get(0);
        assertThat(request.name()).isEqualTo(new SubdomainName("quiet-otter-4f2a"));
        assertThat(request.host()).isEqualTo("quiet-otter-4f2a.example.dev");
        assertThat(request.head().uri()).isEqualTo("/hello?x=1");
        assertThat(edge.exchanges.contentBytes).containsExactly(0);
        assertThat(edge.written()).isEmpty();
        assertThat(edge.channel.pipeline().get(ConnectPipeline.CONNECT_FILTER)).isNull();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "a.quiet-otter.example.dev",
                "example.dev",
                "other.test",
                "quiet_otter.example.dev",
                "ab.example.dev",
                "quiet-otter.example.dev.evil.test",
                "[::1]:7080",
                "192.0.2.1"
            })
    void anyOtherHostIsTunnelNotFound(String host) {
        edge.send(get(host, "/"));

        String response = edge.written();
        assertThat(response).startsWith("HTTP/1.1 404 Not Found").contains("jburrow-error: TUNNEL_NOT_FOUND");
        assertThat(edge.exchanges.requests).isEmpty();
        assertThat(edge.channel.isOpen()).isTrue();
    }

    @Test
    void twoHostHeadersAreABadRequest() {
        edge.send("GET / HTTP/1.1\r\nHost: a-tunnel.example.dev\r\nHost: b-tunnel.example.dev\r\n\r\n");

        assertThat(edge.written()).startsWith("HTTP/1.1 400 Bad Request").contains("jburrow-error: BAD_REQUEST");
        assertThat(edge.exchanges.requests).isEmpty();
    }

    @Test
    void noHostHeaderIsABadRequest() {
        edge.send("GET / HTTP/1.1\r\n\r\n");

        assertThat(edge.written()).startsWith("HTTP/1.1 400 Bad Request").contains("jburrow-error: BAD_REQUEST");
    }

    @Test
    void aRequestThatDoesNotDecodeIsABadRequestAndClosesTheConnection() {
        edge.send("GET / HTTP/1.1\r\nHost: a-tunnel.example.dev\r\nX-Big: " + "x".repeat(9_000) + "\r\n\r\n");

        assertThat(edge.written())
                .startsWith("HTTP/1.1 400 Bad Request")
                .contains("jburrow-error: BAD_REQUEST", "connection: close");
        assertThat(edge.channel.isOpen()).isFalse();
    }

    @Test
    void theRequestPathNeverAppearsOnAPage() {
        edge.send(get("nowhere.test", "/secret-token-a1b2c3?key=hunter2"));

        assertThat(edge.written()).doesNotContain("secret-token-a1b2c3", "hunter2");
    }

    @Test
    void autoReadIsOffForAnExchangeAndOnAgainWhenItEnds() {
        edge.send(get("quiet-otter.example.dev", "/"));
        assertThat(edge.channel.config().isAutoRead()).isFalse();

        edge.exchanges.end();

        assertThat(edge.channel.config().isAutoRead()).isTrue();
    }

    @Test
    void aPageWithoutABodyEndsItsExchangeAtOnce() {
        edge.send(get("nowhere.test", "/"));

        assertThat(edge.written()).contains("TUNNEL_NOT_FOUND");
        assertThat(edge.channel.config().isAutoRead()).isTrue();
        assertThat(edge.channel.isOpen()).isTrue();
    }

    @Test
    void aPageDiscardsTheBodyAndKeepsTheConnectionForTheNextRequest() {
        edge.send("POST / HTTP/1.1\r\nHost: nowhere.test\r\nContent-Length: 5\r\n\r\n");
        assertThat(edge.written()).contains("TUNNEL_NOT_FOUND").doesNotContain("connection: close");
        assertThat(edge.channel.config().isAutoRead())
                .as("reading the body to discard it")
                .isTrue();

        edge.send("hello");
        edge.send(get("quiet-otter.example.dev", "/next"));

        assertThat(edge.exchanges.requests)
                .singleElement()
                .satisfies(request -> assertThat(request.head().uri()).isEqualTo("/next"));
    }

    @Test
    void aChunkedBodyPastTheLimitClosesTheConnectionAfterThePage() {
        edge.send("POST / HTTP/1.1\r\nHost: nowhere.test\r\nTransfer-Encoding: chunked\r\n\r\n");
        assertThat(edge.written()).contains("TUNNEL_NOT_FOUND");

        String chunk = "x".repeat(600);
        edge.send(Integer.toHexString(chunk.length()) + "\r\n" + chunk + "\r\n");
        assertThat(edge.channel.isOpen()).isTrue();
        edge.send(Integer.toHexString(chunk.length()) + "\r\n" + chunk + "\r\n");

        assertThat(edge.channel.isOpen()).isFalse();
    }

    @Test
    void aDeclaredBodyPastTheLimitGetsThePageAndTheConnectionCloses() {
        edge.send("POST / HTTP/1.1\r\nHost: nowhere.test\r\nContent-Length: 2048\r\n\r\n");

        assertThat(edge.written()).contains("TUNNEL_NOT_FOUND", "connection: close");
        assertThat(edge.channel.isOpen()).isFalse();
    }

    @Test
    void aBodyWaitingFor100ContinueIsNotWaitedFor() {
        edge.send("POST / HTTP/1.1\r\nHost: nowhere.test\r\nContent-Length: 5\r\nExpect: 100-continue\r\n\r\n");

        assertThat(edge.written()).doesNotContain("100 Continue").contains("TUNNEL_NOT_FOUND", "connection: close");
        assertThat(edge.channel.isOpen()).isFalse();
    }

    @Test
    void aVisitorThatAsksToCloseIsClosedAfterThePage() {
        edge.send("GET / HTTP/1.1\r\nHost: nowhere.test\r\nConnection: close\r\n\r\n");

        assertThat(edge.written()).contains("TUNNEL_NOT_FOUND", "connection: close");
        assertThat(edge.channel.isOpen()).isFalse();
    }

    @Test
    void aPipelinedRequestWaitsForTheExchangeBeforeIt() {
        edge.send(get("first-tunnel.example.dev", "/1") + get("second-tunnel.example.dev", "/2"));

        assertThat(edge.exchanges.requests).extracting(r -> r.head().uri()).containsExactly("/1");

        edge.exchanges.end();

        assertThat(edge.exchanges.requests).extracting(r -> r.head().uri()).containsExactly("/1", "/2");
        assertThat(edge.channel.config().isAutoRead()).isFalse();
    }

    @Test
    void pipelinedPagesAreAnsweredInOrder() {
        edge.send(get("one.test", "/") + get("two.test", "/") + get("three.test", "/"));

        String written = edge.written();
        assertThat(written.split("HTTP/1.1 404", -1)).hasSize(4);
        assertThat(written.indexOf("one.test"))
                .isLessThan(written.indexOf("two.test"))
                .isLessThan(written.indexOf("three.test"));
    }

    @Test
    void theExchangeHandlerIsAddedOnceAndRemovedForAConnectUpgrade() {
        edge.send(get("quiet-otter.example.dev", "/"));
        edge.exchanges.end();
        edge.send(get("quiet-otter.example.dev", "/"));
        edge.exchanges.end();
        assertThat(edge.exchanges.requests).hasSize(2);
        assertThat(HostDispatcher.carriesTunnels(edge.channel)).isTrue();

        edge.send(upgrade(CONNECT_HOST));

        assertThat(edge.written()).startsWith("HTTP/1.1 101");
        assertThat(edge.upgrades).hasSize(1);
        assertThat(edge.exchanges.added()).isFalse();
    }

    @Test
    void anExchangeThatEndsBeforeItsRequestClosesTheConnection() {
        edge.send("POST / HTTP/1.1\r\nHost: quiet-otter.example.dev\r\nContent-Length: 10\r\n\r\n");

        edge.exchanges.end();

        assertThat(edge.channel.isOpen()).isFalse();
    }

    @Test
    void hostsAreNormalizedWithoutPortBracketsOrAsciiCase() {
        assertThat(HostDispatcher.normalize(" Quiet-Otter.Example.DEV:443 ")).isEqualTo("quiet-otter.example.dev");
        assertThat(HostDispatcher.normalize("[::1]:7080")).isEqualTo("::1");
        assertThat(HostDispatcher.normalize("[::1]")).isEqualTo("::1");
        assertThat(HostDispatcher.normalize("Àb.example.dev")).isEqualTo("Àb.example.dev");
    }
}
