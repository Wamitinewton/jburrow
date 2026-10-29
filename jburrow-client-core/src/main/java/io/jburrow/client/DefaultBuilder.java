package io.jburrow.client;

import io.jburrow.client.tunnel.adapter.netty.NettyTunnelClient;
import io.jburrow.client.tunnel.application.port.TunnelEvents;
import io.jburrow.client.tunnel.domain.TunnelSpec;
import io.jburrow.protocol.control.Windows;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import javax.net.ssl.TrustManagerFactory;

/** Collects settings; {@link #build()} validates them all at once through {@link ClientSettings}. */
final class DefaultBuilder implements JBurrowClient.Builder {

    private final List<TunnelSpec> tunnels = new ArrayList<>();
    private URI server;
    private String authtoken;
    private TunnelEvents events = new TunnelEvents() {};
    private String clientName = ClientSettings.DEFAULT_CLIENT_NAME;
    private String clientVersion = ClientSettings.defaultClientVersion();
    private TrustManagerFactory trustManager;
    private Duration handshakeTimeout = ClientSettings.DEFAULT_HANDSHAKE_TIMEOUT;
    private Windows windows = ClientSettings.DEFAULT_WINDOWS;

    @Override
    public JBurrowClient.Builder server(URI uri) {
        this.server = Objects.requireNonNull(uri, "uri");
        return this;
    }

    @Override
    public JBurrowClient.Builder authtoken(String token) {
        this.authtoken = Objects.requireNonNull(token, "token");
        return this;
    }

    @Override
    public JBurrowClient.Builder tunnel(TunnelSpec spec) {
        tunnels.add(Objects.requireNonNull(spec, "spec"));
        return this;
    }

    @Override
    public JBurrowClient.Builder events(TunnelEvents listener) {
        this.events = Objects.requireNonNull(listener, "listener");
        return this;
    }

    @Override
    public JBurrowClient.Builder clientInfo(String name, String version) {
        this.clientName = Objects.requireNonNull(name, "name");
        this.clientVersion = Objects.requireNonNull(version, "version");
        return this;
    }

    @Override
    public JBurrowClient.Builder trustManager(TrustManagerFactory trustManagerFactory) {
        this.trustManager = Objects.requireNonNull(trustManagerFactory, "trustManagerFactory");
        return this;
    }

    @Override
    public JBurrowClient.Builder handshakeTimeout(Duration timeout) {
        this.handshakeTimeout = Objects.requireNonNull(timeout, "timeout");
        return this;
    }

    @Override
    public JBurrowClient.Builder windows(Windows receiveWindows) {
        this.windows = Objects.requireNonNull(receiveWindows, "receiveWindows");
        return this;
    }

    @Override
    public JBurrowClient build() {
        return new NettyTunnelClient(new ClientSettings(
                server,
                authtoken,
                tunnels,
                events,
                ClientSettings.clientInfo(clientName, clientVersion),
                Optional.ofNullable(trustManager),
                handshakeTimeout,
                windows));
    }
}
