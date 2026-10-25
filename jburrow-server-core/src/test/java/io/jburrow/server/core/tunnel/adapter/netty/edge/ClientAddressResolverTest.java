package io.jburrow.server.core.tunnel.adapter.netty.edge;

import static org.assertj.core.api.Assertions.assertThat;

import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpVersion;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.List;
import org.junit.jupiter.api.Test;

class ClientAddressResolverTest {

    private static final InetAddress PROXY = address("10.42.0.7");
    private static final InetAddress STRANGER = address("198.51.100.20");

    private final ClientAddressResolver resolver = new ClientAddressResolver(List.of(Cidr.parse("10.42.0.0/16")));

    @Test
    void anUntrustedPeerIsTheClientWhateverTheHeaderSays() {
        InetAddress client = resolve(STRANGER, "1.2.3.4");

        assertThat(client).isEqualTo(STRANGER);
    }

    @Test
    void aTrustedPeerPassesOnTheFirstUntrustedHopFromTheRight() {
        assertThat(resolve(PROXY, "1.2.3.4, 10.42.0.7")).isEqualTo(address("1.2.3.4"));
    }

    @Test
    void aSpoofedLeftmostEntryIsNotBelieved() {
        assertThat(resolve(PROXY, "6.6.6.6, 1.2.3.4, 10.42.1.1")).isEqualTo(address("1.2.3.4"));
    }

    @Test
    void severalHeadersAreReadAsOneListInOrder() {
        assertThat(resolve(PROXY, "6.6.6.6", "1.2.3.4", "10.42.3.3")).isEqualTo(address("1.2.3.4"));
    }

    @Test
    void anEntryThatIsNotAnIpLiteralFallsBackToThePeer() {
        assertThat(resolve(PROXY, "1.2.3.4, unknown")).isEqualTo(PROXY);
        assertThat(resolve(PROXY, "1.2.3.4, localhost")).isEqualTo(PROXY);
        assertThat(resolve(PROXY, "1.2.3.4,,10.42.0.1")).isEqualTo(PROXY);
    }

    @Test
    void anUntrustedHopRightOfGarbageStillWins() {
        assertThat(resolve(PROXY, "garbage, 1.2.3.4")).isEqualTo(address("1.2.3.4"));
    }

    @Test
    void whenEveryEntryIsTrustedTheLeftmostIsTheClient() {
        assertThat(resolve(PROXY, "10.42.9.9, 10.42.0.7")).isEqualTo(address("10.42.9.9"));
    }

    @Test
    void noHeaderMeansThePeer() {
        assertThat(resolve(PROXY)).isEqualTo(PROXY);
    }

    @Test
    void readsIpv6Entries() {
        assertThat(resolve(PROXY, "2001:db8::1")).isEqualTo(address("2001:db8::1"));
    }

    @Test
    void storesTheResolvedAddressOnTheChannelAndPassesTheRequestOn() {
        EmbeddedChannel channel = channelFrom(PROXY);
        FullHttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/v1/connect");
        request.headers().add(ClientAddressResolver.FORWARDED_FOR, "203.0.113.9");

        channel.writeInbound(request);

        assertThat(ClientAddressResolver.clientAddress(channel)).isEqualTo(address("203.0.113.9"));
        FullHttpRequest passed = channel.readInbound();
        assertThat(passed).isSameAs(request);
        passed.release();
        channel.finishAndReleaseAll();
    }

    @Test
    void beforeAnyRequestTheClientIsThePeer() {
        EmbeddedChannel channel = channelFrom(STRANGER);

        assertThat(ClientAddressResolver.clientAddress(channel)).isEqualTo(STRANGER);
        channel.finishAndReleaseAll();
    }

    private InetAddress resolve(InetAddress peer, String... headers) {
        return resolver.resolve(peer, List.of(headers));
    }

    private EmbeddedChannel channelFrom(InetAddress peer) {
        return new PeerChannel(new InetSocketAddress(peer, 40_000), resolver);
    }

    static InetAddress address(String literal) {
        return Cidr.parseAddress(literal).orElseThrow();
    }
}
