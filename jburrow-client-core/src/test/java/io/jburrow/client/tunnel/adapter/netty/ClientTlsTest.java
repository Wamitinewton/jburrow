package io.jburrow.client.tunnel.adapter.netty;

import static org.assertj.core.api.Assertions.assertThat;

import io.netty.buffer.ByteBufAllocator;
import java.util.Optional;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLParameters;
import org.junit.jupiter.api.Test;

/** The client's TLS engine as {@code docs/protocol.md} §3.2 requires it, before any handshake. */
class ClientTlsTest {

    @Test
    void theEngineOffersTls13And12AndVerifiesTheHostItSendsAsSni() throws Exception {
        SSLEngine engine = ClientTls.forServer("connect.example.dev", 443, Optional.empty())
                .newHandler(ByteBufAllocator.DEFAULT)
                .engine();
        SSLParameters parameters = engine.getSSLParameters();

        assertThat(engine.getUseClientMode()).isTrue();
        assertThat(engine.getEnabledProtocols()).containsExactlyInAnyOrder("TLSv1.3", "TLSv1.2");
        assertThat(parameters.getEndpointIdentificationAlgorithm()).isEqualTo("HTTPS");
        assertThat(parameters.getServerNames()).containsExactly(new SNIHostName("connect.example.dev"));
        assertThat(engine.getPeerHost()).isEqualTo("connect.example.dev");
    }

    @Test
    void aNameWithoutADotIsStillSentAsSni() throws Exception {
        SSLParameters parameters = ClientTls.forServer("localhost", 7443, Optional.empty())
                .newHandler(ByteBufAllocator.DEFAULT)
                .engine()
                .getSSLParameters();

        assertThat(parameters.getServerNames()).containsExactly(new SNIHostName("localhost"));
    }

    @Test
    void anIpLiteralIsVerifiedButNotSentAsSni() throws Exception {
        SSLEngine engine = ClientTls.forServer("127.0.0.1", 7443, Optional.empty())
                .newHandler(ByteBufAllocator.DEFAULT)
                .engine();

        assertThat(engine.getSSLParameters().getServerNames()).isNullOrEmpty();
        assertThat(engine.getSSLParameters().getEndpointIdentificationAlgorithm())
                .isEqualTo("HTTPS");
    }
}
