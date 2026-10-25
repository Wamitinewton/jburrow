package io.jburrow.client.tunnel.adapter.netty;

import static org.assertj.core.api.Assertions.assertThat;

import io.jburrow.client.JBurrowClient;
import io.jburrow.client.TunnelHandle;
import io.jburrow.client.TunnelStartException;
import io.jburrow.client.tunnel.domain.LocalTarget;
import io.jburrow.client.tunnel.domain.TunnelSpec;
import io.jburrow.protocol.adapter.netty.Frame;
import io.jburrow.protocol.control.Registered;
import io.jburrow.protocol.control.RegisteredTunnel;
import io.jburrow.protocol.control.ServerHello;
import io.jburrow.protocol.control.ServerInfo;
import io.jburrow.protocol.control.SessionLimits;
import io.jburrow.protocol.control.TunnelKind;
import io.jburrow.protocol.testsupport.ScriptedPeer;
import io.jburrow.protocol.wire.ProtocolVersion;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslProvider;
import io.netty.pkitesting.CertificateBuilder;
import io.netty.pkitesting.X509Bundle;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.TrustManagerFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The client's TLS against a {@link ScriptedPeer} behind TLS, {@code docs/protocol.md} §3.2: the chain is verified, the
 * host name is verified against the certificate, SNI carries the host name, and ALPN offers only {@code http/1.1}.
 */
class ClientTlsIntegrationTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final String TOKEN = "jb_" + "t".repeat(43);
    private static final UUID SESSION_ID = UUID.fromString("0b8e5c1e-7c55-4c46-9a3e-2f1d0c9e4b7a");
    private static final UUID TUNNEL_ID = UUID.fromString("5d0f4f0e-31a6-4a39-8d51-0c0f7f1f6c23");

    private static X509Bundle root;
    private static X509Bundle untrustedRoot;
    private static SslContext serverContext;

    private final ExecutorService threads = Executors.newVirtualThreadPerTaskExecutor();
    private ScriptedPeer peer;
    private JBurrowClient client;

    @BeforeAll
    static void certificates() throws Exception {
        root = new CertificateBuilder()
                .subject("CN=JBurrow Test Root")
                .setIsCertificateAuthority(true)
                .ecp256()
                .buildSelfSigned();
        X509Bundle localhost = new CertificateBuilder()
                .subject("CN=localhost")
                .addSanDnsName("localhost")
                .addExtendedKeyUsageServerAuth()
                .ecp256()
                .buildIssuedBy(root);
        untrustedRoot = new CertificateBuilder()
                .subject("CN=Somebody Else's Root")
                .setIsCertificateAuthority(true)
                .ecp256()
                .buildSelfSigned();
        serverContext = SslContextBuilder.forServer(localhost.toKeyManagerFactory())
                .sslProvider(SslProvider.JDK)
                .build();
    }

    @AfterEach
    void stop() {
        if (client != null) {
            client.close();
        }
        if (peer != null) {
            peer.close();
        }
        threads.shutdownNow();
    }

    @Test
    void aTrustedCertificateForTheHostConnectsWithSniAndOnlyHttp11Offered() throws Exception {
        peer = ScriptedPeer.listenTls(0, serverContext, ProtocolVersion.CURRENT);
        Future<TunnelHandle> start = start("wss://localhost:" + peer.port() + "/v1/connect", root);

        peer.expect(Frame.Hello.class, TIMEOUT);
        peer.expect(Frame.Register.class, TIMEOUT);
        peer.send(new Frame.ServerHello(new ServerHello(
                SESSION_ID,
                new ServerInfo("jburrow-server", "0.2.0"),
                List.of(),
                new SessionLimits(65_536, 1, 1, 15_000, 45_000))));
        peer.send(new Frame.Registered(new Registered(
                List.of(RegisteredTunnel.of("default", TUNNEL_ID, TunnelKind.TCP, "localhost", 21_000)))));

        assertThat(start.get(TIMEOUT.toSeconds(), TimeUnit.SECONDS).sessionId()).isEqualTo(SESSION_ID.toString());
        ScriptedPeer.TlsHello hello = peer.tlsHello();
        assertThat(hello.serverNames()).containsExactly("localhost");
        assertThat(hello.applicationProtocols()).containsExactly("http/1.1");
    }

    @Test
    void aCertificateForAnotherHostFailsHostVerification() throws Exception {
        peer = ScriptedPeer.listenTls(0, serverContext, ProtocolVersion.CURRENT);

        TunnelStartException failure = failure(start("wss://127.0.0.1:" + peer.port() + "/v1/connect", root));

        assertThat(failure.kind()).isEqualTo(TunnelStartException.Kind.TLS_FAILED);
        assertThat(failure).hasMessageContaining("127.0.0.1");
    }

    @Test
    void anUntrustedCertificateFailsChainVerification() throws Exception {
        peer = ScriptedPeer.listenTls(0, serverContext, ProtocolVersion.CURRENT);

        TunnelStartException failure = failure(start("wss://localhost:" + peer.port() + "/v1/connect", untrustedRoot));

        assertThat(failure.kind()).isEqualTo(TunnelStartException.Kind.TLS_FAILED);
        assertThat(failure.code()).isEmpty();
    }

    @Test
    void theSystemTrustStoreDoesNotTrustATestCertificate() throws Exception {
        peer = ScriptedPeer.listenTls(0, serverContext, ProtocolVersion.CURRENT);

        TunnelStartException failure = failure(start("wss://localhost:" + peer.port() + "/v1/connect", null));

        assertThat(failure.kind()).isEqualTo(TunnelStartException.Kind.TLS_FAILED);
    }

    private Future<TunnelHandle> start(String url, X509Bundle trusted) {
        JBurrowClient.Builder builder = JBurrowClient.builder()
                .server(URI.create(url))
                .authtoken(TOKEN)
                .tunnel(TunnelSpec.tcp("default", LocalTarget.of("localhost", 22)));
        if (trusted != null) {
            TrustManagerFactory trustManager = trusted.toTrustManagerFactory();
            builder.trustManager(trustManager);
        }
        client = builder.build();
        return threads.submit(client::start);
    }

    private static TunnelStartException failure(Future<TunnelHandle> start) throws Exception {
        try {
            start.get(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof TunnelStartException failure) {
                return failure;
            }
            throw e;
        }
        throw new AssertionError("start() succeeded");
    }
}
