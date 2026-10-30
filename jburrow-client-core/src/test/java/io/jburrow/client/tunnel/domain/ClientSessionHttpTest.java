package io.jburrow.client.tunnel.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import io.jburrow.protocol.control.BodyFraming;
import io.jburrow.protocol.control.ClientInfo;
import io.jburrow.protocol.control.HeaderFields;
import io.jburrow.protocol.control.HttpRequestHead;
import io.jburrow.protocol.control.HttpResponseHead;
import io.jburrow.protocol.control.Open;
import io.jburrow.protocol.control.Registered;
import io.jburrow.protocol.control.RegisteredTunnel;
import io.jburrow.protocol.control.RemoteAddress;
import io.jburrow.protocol.control.ServerHello;
import io.jburrow.protocol.control.ServerInfo;
import io.jburrow.protocol.control.SessionLimits;
import io.jburrow.protocol.control.SubdomainName;
import io.jburrow.protocol.control.TunnelKind;
import io.jburrow.protocol.control.TunnelRequest;
import io.jburrow.protocol.control.Windows;
import io.jburrow.protocol.flow.DataOutcome;
import io.jburrow.protocol.stream.StreamEvent;
import io.jburrow.protocol.stream.StreamState;
import io.jburrow.protocol.wire.Capability;
import io.jburrow.protocol.wire.ErrorCode;
import io.jburrow.protocol.wire.ProtocolException;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;

/** {@link ClientSession} with the {@code http} capability: negotiation, and the rules of an HTTP stream both ways. */
class ClientSessionHttpTest {

    private static final UUID SESSION_ID = UUID.fromString("0b8e5c1e-7c55-4c46-9a3e-2f1d0c9e4b7a");
    private static final UUID TUNNEL_ID = UUID.fromString("5d0f4f0e-31a6-4a39-8d51-0c0f7f1f6c23");
    private static final LocalTarget WEB = LocalTarget.of("localhost", 3_000);
    private static final SessionLimits LIMITS = new SessionLimits(65_536, 1, 100, 15_000, 45_000);
    private static final RemoteAddress VISITOR = new RemoteAddress("203.0.113.9", 51_234);
    private static final Windows WINDOWS = new Windows(262_144, 524_288);
    private static final ClientInfo CLIENT = new ClientInfo("jburrow-test", "1.0.0", null, null);
    private static final Set<Capability> HTTP = Set.of(Capability.HTTP);

    private static final HttpRequestHead GET = request("GET", BodyFraming.EMPTY, "host", "x.example.dev");
    private static final HttpRequestHead POST_5 =
            request("POST", BodyFraming.LENGTH, "host", "x.example.dev", "content-length", "5");
    private static final HttpResponseHead OK_5 =
            new HttpResponseHead(200, HeaderFields.of("content-length", "5"), BodyFraming.LENGTH);
    private static final HttpResponseHead OK_EMPTY = new HttpResponseHead(200, HeaderFields.of(), BodyFraming.EMPTY);
    private static final HttpResponseHead EARLY_HINTS =
            new HttpResponseHead(103, HeaderFields.of("link", "</a.css>; rel=preload"), null);

    private final ClientSession session = new ClientSession(List.of(TunnelSpec.http("default", WEB)), WINDOWS, HTTP);

    // Negotiation, docs/protocol.md §4.2 and §8.3.

    @Test
    void theHelloOffersEveryCapabilityTheSessionWasGiven() {
        assertThat(session.hello(CLIENT).capabilities()).containsExactly("http");
        assertThat(new ClientSession(List.of(TunnelSpec.tcp("default", WEB)), WINDOWS)
                        .hello(CLIENT)
                        .capabilities())
                .isEmpty();
    }

    @Test
    void anHttpTunnelRegistersOnlyAfterTheServersHello() {
        assertThat(session.registersAfterHello()).isTrue();
        assertThat(new ClientSession(List.of(TunnelSpec.tcp("default", WEB)), WINDOWS, HTTP).registersAfterHello())
                .isFalse();
    }

    @Test
    void anHttpTunnelNeedsASessionThatOffersHttp() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ClientSession(List.of(TunnelSpec.http("default", WEB)), WINDOWS))
                .withMessageContaining("http capability");
    }

    @Test
    void theSessionHasTheCapabilitiesTheServerListsThatItOffered() {
        session.onUpgraded();
        assertThat(session.httpEnabled()).isFalse();

        session.onServerHello(serverHello(List.of("http", "something-else")));

        assertThat(session.capabilities()).containsExactly(Capability.HTTP);
        assertThat(session.httpEnabled()).isTrue();
        assertThat(session.missingCapability()).isEmpty();
    }

    @Test
    void aCapabilityTheClientDidntOfferIsIgnored() {
        ClientSession tcpOnly = new ClientSession(List.of(TunnelSpec.tcp("default", WEB)), WINDOWS);
        tcpOnly.onUpgraded();

        tcpOnly.onServerHello(serverHello(List.of("http")));

        assertThat(tcpOnly.capabilities()).isEmpty();
        assertThat(tcpOnly.httpEnabled()).isFalse();
        assertThat(tcpOnly.missingCapability()).isEmpty();
    }

    @Test
    void aServerWithoutHttpLeavesAnHttpTunnelMissingIt() {
        session.onUpgraded();

        session.onServerHello(serverHello(List.of()));

        assertThat(session.httpEnabled()).isFalse();
        assertThat(session.missingCapability()).contains(Capability.HTTP);
    }

    @Test
    void theRegisterAsksForTheSubdomain() {
        ClientSession named = new ClientSession(
                List.of(TunnelSpec.http("default", WEB).subdomain(new SubdomainName("myapp"))), WINDOWS, HTTP);

        assertThat(named.register("jb_" + "t".repeat(43)).tunnels())
                .containsExactly(new TunnelRequest("default", TunnelKind.HTTP, "myapp", null));
    }

    @Test
    void aReplyOnAnotherSubdomainThanTheOneAskedForIsAProtocolError() {
        ClientSession named = new ClientSession(
                List.of(TunnelSpec.http("default", WEB).subdomain(new SubdomainName("myapp"))), WINDOWS, HTTP);
        named.onUpgraded();
        named.onServerHello(serverHello(List.of("http")));

        assertProtocolError(() -> named.onRegistered(new Registered(
                List.of(RegisteredTunnel.http("default", TUNNEL_ID, "https", "other.example.dev", 443)))));
    }

    @Test
    void theRequestedSubdomainIsAccepted() {
        ClientSession named = new ClientSession(
                List.of(TunnelSpec.http("default", WEB).subdomain(new SubdomainName("myapp"))), WINDOWS, HTTP);
        named.onUpgraded();
        named.onServerHello(serverHello(List.of("http")));

        List<ActiveTunnel> tunnels = named.onRegistered(new Registered(
                List.of(RegisteredTunnel.http("default", TUNNEL_ID, "https", "myapp.example.dev", 443))));

        assertThat(tunnels.getFirst().registered().url()).isEqualTo("https://myapp.example.dev");
    }

    // The request, from the server: its violations are the server's.

    @Test
    void aWholeExchangeFollowsTheRules() {
        established();
        session.onOpen(1, open(POST_5));

        assertThat(session.onDataReceived(1, 5)).isEqualTo(DataOutcome.DELIVER);
        session.onStreamFrame(1, StreamEvent.CLOSE_RECEIVED);
        session.onHeadersSent(1, EARLY_HINTS);
        session.onHeadersSent(1, OK_5);
        session.onDataSent(1, 5);
        session.onStreamSent(1, StreamEvent.CLOSE_SENT);

        assertThat(session.streamState(1)).isEmpty();
    }

    @Test
    void requestDataPastItsContentLengthIsTheServersProtocolError() {
        established();
        session.onOpen(1, open(POST_5));
        session.onDataReceived(1, 4);

        assertProtocolError(() -> session.onDataReceived(1, 2));
    }

    @Test
    void requestDataOnAnEmptyBodyIsTheServersProtocolError() {
        established();
        session.onOpen(1, open(GET));

        assertProtocolError(() -> session.onDataReceived(1, 1));
    }

    @Test
    void aRequestEndingShortOfItsContentLengthIsTheServersProtocolError() {
        established();
        session.onOpen(1, open(POST_5));
        session.onDataReceived(1, 4);

        assertProtocolError(() -> session.onStreamFrame(1, StreamEvent.CLOSE_RECEIVED));
    }

    @Test
    void aResetFromTheServerEndsTheExchangeWhateverItsBody() {
        established();
        session.onOpen(1, open(POST_5));
        session.onDataReceived(1, 2);

        session.onStreamFrame(1, StreamEvent.RST_RECEIVED);

        assertThat(session.streamState(1)).isEmpty();
    }

    @Test
    void aTcpStreamHasNoExchangeRules() {
        ClientSession both = new ClientSession(List.of(TunnelSpec.tcp("default", WEB)), WINDOWS, HTTP);
        both.onUpgraded();
        both.onServerHello(serverHello(List.of("http")));
        both.onRegistered(new Registered(
                List.of(RegisteredTunnel.of("default", TUNNEL_ID, TunnelKind.TCP, "tcp.example.dev", 20_417))));
        both.onOpen(1, new Open(TUNNEL_ID, TunnelKind.TCP, VISITOR));

        assertThat(both.onDataReceived(1, 10)).isEqualTo(DataOutcome.DELIVER);
        both.onDataSent(1, 10);
        assertThatIllegalStateException().isThrownBy(() -> both.onHeadersSent(1, OK_EMPTY));
    }

    // The response, from this client: breaking its rules is a bug here.

    @Test
    void theResponseIsCompleteWhileTheRequestBodyIsStillArriving() {
        established();
        session.onOpen(1, open(request("POST", BodyFraming.CHUNKED, "host", "x")));
        session.onDataReceived(1, 100);

        session.onHeadersSent(1, new HttpResponseHead(413, HeaderFields.of(), BodyFraming.EMPTY));
        session.onStreamSent(1, StreamEvent.CLOSE_SENT);

        assertThat(session.streamState(1)).contains(StreamState.HALF_CLOSED_LOCAL);
        session.onStreamSent(1, StreamEvent.RST_SENT);
        assertThat(session.streamState(1)).isEmpty();
    }

    @Test
    void dataBeforeTheFinalHeadIsABugHere() {
        established();
        session.onOpen(1, open(GET));
        session.onHeadersSent(1, EARLY_HINTS);

        assertThatIllegalStateException().isThrownBy(() -> session.onDataSent(1, 1));
    }

    @Test
    void aSecondFinalHeadIsABugHere() {
        established();
        session.onOpen(1, open(GET));
        session.onHeadersSent(1, OK_EMPTY);

        assertThatIllegalStateException().isThrownBy(() -> session.onHeadersSent(1, OK_EMPTY));
    }

    @Test
    void endingAResponseShortOfItsContentLengthIsABugHere() {
        established();
        session.onOpen(1, open(GET));
        session.onHeadersSent(1, OK_5);
        session.onDataSent(1, 4);

        assertThatIllegalStateException().isThrownBy(() -> session.onStreamSent(1, StreamEvent.CLOSE_SENT));
    }

    @Test
    void aBodyAnsweringHeadIsABugHere() {
        established();
        session.onOpen(1, open(request("HEAD", BodyFraming.EMPTY, "host", "x")));

        assertThatIllegalStateException().isThrownBy(() -> session.onHeadersSent(1, OK_5));
    }

    @Test
    void headersAfterThisClientsCloseAreABugHere() {
        established();
        session.onOpen(1, open(request("POST", BodyFraming.CHUNKED, "host", "x")));
        session.onHeadersSent(1, OK_EMPTY);
        session.onStreamSent(1, StreamEvent.CLOSE_SENT);

        assertThatIllegalStateException().isThrownBy(() -> session.onHeadersSent(1, OK_EMPTY));
    }

    private void established() {
        session.onUpgraded();
        session.onServerHello(serverHello(List.of("http")));
        session.onRegistered(new Registered(
                List.of(RegisteredTunnel.http("default", TUNNEL_ID, "https", "quiet-otter-4f2a.example.dev", 443))));
    }

    private static ServerHello serverHello(List<String> capabilities) {
        return new ServerHello(SESSION_ID, new ServerInfo("jburrow-server", "0.3.0"), capabilities, LIMITS, WINDOWS);
    }

    private static Open open(HttpRequestHead request) {
        return new Open(TUNNEL_ID, TunnelKind.HTTP, VISITOR, request);
    }

    private static HttpRequestHead request(String method, BodyFraming body, String... namesAndValues) {
        return new HttpRequestHead(method, "/items?page=2", HeaderFields.of(namesAndValues), body);
    }

    private static void assertProtocolError(ThrowingCallable call) {
        assertThatExceptionOfType(ProtocolException.class)
                .isThrownBy(call)
                .satisfies(e -> assertThat(e.code()).isEqualTo(ErrorCode.PROTOCOL_ERROR));
    }
}
