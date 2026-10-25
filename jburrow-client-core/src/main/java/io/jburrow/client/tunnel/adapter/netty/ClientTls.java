package io.jburrow.client.tunnel.adapter.netty;

import io.netty.buffer.ByteBufAllocator;
import io.netty.handler.ssl.ApplicationProtocolConfig;
import io.netty.handler.ssl.ApplicationProtocolNames;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslHandler;
import io.netty.handler.ssl.SslProvider;
import io.netty.util.NetUtil;
import java.util.Objects;
import java.util.Optional;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLException;
import javax.net.ssl.TrustManagerFactory;

/**
 * TLS for the control connection, as {@code docs/protocol.md} §3.2 requires of a client: the certificate chain verified
 * against the trust store and the host name against the certificate, the host name sent as SNI, TLS 1.3 and 1.2 only,
 * and ALPN offering exactly {@code http/1.1}, so no intermediary negotiates HTTP/2, where the WebSocket would need
 * RFC 8441.
 *
 * <p>The JDK provider only: {@code netty-tcnative} would complicate native-image and cross-platform builds.
 */
final class ClientTls {

    static final String[] PROTOCOLS = {"TLSv1.3", "TLSv1.2"};

    private final SslContext context;
    private final String host;
    private final int port;

    private ClientTls(SslContext context, String host, int port) {
        this.context = context;
        this.host = host;
        this.port = port;
    }

    /**
     * TLS to {@code host}, the server's name as the URL gives it, verified against {@code trustManager} or, when it is
     * empty, the JDK's default trust store.
     */
    static ClientTls forServer(String host, int port, Optional<TrustManagerFactory> trustManager) throws SSLException {
        Objects.requireNonNull(host, "host");
        SslContextBuilder builder = SslContextBuilder.forClient()
                .sslProvider(SslProvider.JDK)
                .protocols(PROTOCOLS)
                .endpointIdentificationAlgorithm("HTTPS")
                .applicationProtocolConfig(new ApplicationProtocolConfig(
                        ApplicationProtocolConfig.Protocol.ALPN,
                        ApplicationProtocolConfig.SelectorFailureBehavior.NO_ADVERTISE,
                        ApplicationProtocolConfig.SelectedListenerFailureBehavior.ACCEPT,
                        ApplicationProtocolNames.HTTP_1_1));
        trustManager.ifPresent(builder::trustManager);
        // The JDK sends SNI on its own only for names with a dot, so "localhost" would go without. An IP literal
        // never has SNI (RFC 6066 §3).
        if (NetUtil.createInetAddressFromIpAddressString(host) == null) {
            builder.serverName(new SNIHostName(host));
        }
        return new ClientTls(builder.build(), host, port);
    }

    /** A handler that verifies the server as {@code host}, the name the client connects to. */
    SslHandler newHandler(ByteBufAllocator alloc) {
        return context.newHandler(alloc, host, port);
    }
}
