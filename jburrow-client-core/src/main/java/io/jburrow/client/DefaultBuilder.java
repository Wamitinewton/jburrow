package io.jburrow.client;

import io.jburrow.client.tunnel.adapter.netty.NettyTunnelClient;
import io.jburrow.client.tunnel.application.port.TunnelEvents;
import io.jburrow.client.tunnel.domain.HostHeader;
import io.jburrow.client.tunnel.domain.LocalTarget;
import io.jburrow.client.tunnel.domain.TunnelSpec;
import io.jburrow.protocol.control.SubdomainName;
import io.jburrow.protocol.control.Windows;
import io.jburrow.protocol.wire.Capability;
import io.jburrow.protocol.wire.ProtocolVersion;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import javax.net.ssl.TrustManagerFactory;

/** Collects settings; {@link #build()} validates them all at once through {@link ClientSettings}. */
final class DefaultBuilder implements JBurrowClient.Builder {

    static final String HTTP_TUNNEL_REF = "default";

    private final List<TunnelSpec> tunnels = new ArrayList<>();
    private LocalTarget httpTarget;
    private String subdomain;
    private HostHeader hostHeader;
    private URI server;
    private String authtoken;
    private TunnelEvents events = new TunnelEvents() {};
    private String clientName = ClientSettings.DEFAULT_CLIENT_NAME;
    private String clientVersion = ClientSettings.defaultClientVersion();
    private TrustManagerFactory trustManager;
    private Duration handshakeTimeout = ClientSettings.DEFAULT_HANDSHAKE_TIMEOUT;
    private Windows windows = ClientSettings.DEFAULT_WINDOWS;
    private Set<Capability> capabilities = ProtocolVersion.capabilities();

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
    public JBurrowClient.Builder http(LocalTarget target) {
        this.httpTarget = Objects.requireNonNull(target, "target");
        return this;
    }

    @Override
    public JBurrowClient.Builder subdomain(String name) {
        this.subdomain = Objects.requireNonNull(name, "name");
        return this;
    }

    @Override
    public JBurrowClient.Builder hostHeader(HostHeader option) {
        this.hostHeader = Objects.requireNonNull(option, "option");
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
    public JBurrowClient.Builder capabilities(Set<Capability> offered) {
        this.capabilities = Objects.requireNonNull(offered, "offered");
        return this;
    }

    @Override
    public JBurrowClient build() {
        return new NettyTunnelClient(settings());
    }

    /** The validated settings {@link #build()} gives the client. */
    ClientSettings settings() {
        return new ClientSettings(
                server,
                authtoken,
                allTunnels(),
                events,
                ClientSettings.clientInfo(clientName, clientVersion),
                Optional.ofNullable(trustManager),
                handshakeTimeout,
                windows,
                capabilities);
    }

    /** The tunnels from {@link #tunnel}, then the one {@link #http} describes. */
    private List<TunnelSpec> allTunnels() {
        if (httpTarget == null) {
            if (subdomain != null || hostHeader != null) {
                throw new IllegalArgumentException("A subdomain or host header option needs an HTTP tunnel: http(...)");
            }
            return tunnels;
        }
        TunnelSpec http = TunnelSpec.http(HTTP_TUNNEL_REF, httpTarget);
        if (subdomain != null) {
            http = http.subdomain(subdomainName(subdomain));
        }
        if (hostHeader != null) {
            http = http.hostHeader(hostHeader);
        }
        List<TunnelSpec> all = new ArrayList<>(tunnels);
        all.add(http);
        return all;
    }

    private static SubdomainName subdomainName(String name) {
        return SubdomainName.parse(name)
                .orElseThrow(() -> new IllegalArgumentException(
                        "The subdomain must be 3 to 63 lower-case letters, digits and hyphens, without a leading,"
                                + " trailing or doubled hyphen"));
    }
}
