package io.jburrow.client;

import io.jburrow.client.tunnel.application.port.TunnelEvents;
import io.jburrow.client.tunnel.domain.HostHeader;
import io.jburrow.client.tunnel.domain.LocalTarget;
import io.jburrow.client.tunnel.domain.TunnelSpec;
import io.jburrow.protocol.control.Windows;
import io.jburrow.protocol.wire.Capability;
import java.net.URI;
import java.time.Duration;
import java.util.Set;
import javax.net.ssl.TrustManagerFactory;

/**
 * A JBurrow client: one session with a server, carrying the tunnels it asked for. The embeddable entry point; the CLI
 * is one user of it, a test or another program can be another.
 *
 * <pre>{@code
 * try (JBurrowClient client = JBurrowClient.builder()
 *         .server(URI.create("wss://connect.example.dev/v1/connect"))
 *         .authtoken(token)
 *         .tunnel(TunnelSpec.tcp("default", LocalTarget.of("localhost", 22)))
 *         .build()) {
 *     TunnelHandle handle = client.start();
 *     handle.tunnels().forEach(tunnel -> System.out.println(tunnel.url()));
 *     handle.closed().toCompletableFuture().join();
 * }
 * }</pre>
 *
 * <p>An HTTP tunnel is the same with {@code .http(LocalTarget.of("localhost", 3000))} in place of {@code .tunnel(...)},
 * and optionally {@code .subdomain("myapp")} and {@code .hostHeader(HostHeader.REWRITE)}.
 *
 * <p>A client runs one session, on one daemon I/O thread of its own, and is started once. There is no reconnect in this
 * version: when the session ends, {@link TunnelHandle#closed()} says why, and a new client is needed for a new
 * session.
 */
public interface JBurrowClient extends AutoCloseable {

    static Builder builder() {
        return new DefaultBuilder();
    }

    /**
     * Connects, upgrades, and runs the handshake. Blocks until the server answers {@code REGISTER}, or the session
     * fails before that.
     *
     * @throws TunnelStartException if the session couldn't be established, with the reason and, where the server gave
     *     one, its error code
     * @throws IllegalStateException if the client was already started or closed
     */
    TunnelHandle start() throws TunnelStartException;

    /**
     * Closes the session if it is open, waiting briefly for an orderly close, then stops the I/O thread. Called from a
     * {@link TunnelEvents} callback, it doesn't wait.
     */
    @Override
    void close();

    /**
     * Collects a client's settings. {@link #server}, {@link #authtoken} and one tunnel, from {@link #tunnel} or
     * {@link #http}, are required.
     */
    interface Builder {

        /**
         * The connect URL: {@code wss://host[:port]/v1/connect}, or {@code ws://} to this machine only ({@code
         * localhost}, {@code 127.0.0.0/8} or {@code ::1}). A query is dropped.
         */
        Builder server(URI uri);

        /** The credential sent in {@code REGISTER}. Never logged and never part of any {@code toString}. */
        Builder authtoken(String token);

        /** The tunnel to open. This version opens exactly one per client, so this excludes {@link #http}. */
        Builder tunnel(TunnelSpec spec);

        /**
         * Opens an HTTP tunnel to {@code target}, with the ref {@code default}: each request becomes one HTTP/1.1
         * exchange with the local service, on a connection of its own. Excludes {@link #tunnel}; called again, the
         * last target wins.
         */
        Builder http(LocalTarget target);

        /**
         * Asks the server for this name for the {@link #http} tunnel; without it, the server chooses one. Checked
         * against the protocol's rule when the client is built: 3 to 63 lower-case letters, digits and hyphens.
         */
        Builder subdomain(String name);

        /** What the {@link #http} tunnel sends the local service as {@code host}. {@link HostHeader#KEEP} by default. */
        Builder hostHeader(HostHeader option);

        /** Who hears about the session; optional. Called on the client's I/O thread, so it must not block. */
        Builder events(TunnelEvents listener);

        /** The name and version sent in {@code HELLO} and {@code User-Agent}. By default this library's. */
        Builder clientInfo(String name, String version);

        /**
         * Replaces the system trust store for verifying the server's certificate. For tests: host name verification
         * stays on whatever is trusted.
         */
        Builder trustManager(TrustManagerFactory trustManagerFactory);

        /**
         * How long the server has, from the TCP connection opening, to complete TLS and the WebSocket upgrade and to
         * answer {@code HELLO} and {@code REGISTER}. 10 seconds by default.
         */
        Builder handshakeTimeout(Duration timeout);

        /**
         * This client's receive windows, announced to the server: how much it may send on one stream, and on all of
         * them together, before this client returns credit. They bound what the client holds for a slow local
         * service. 256 KiB per stream and 1 MiB for the session by default; {@link Windows} checks the bounds.
         */
        Builder windows(Windows windows);

        /**
         * The capabilities offered in {@code HELLO}. Every one this client implements by default; fewer only to stand
         * in for an older client, as a compatibility test does. A tunnel whose kind needs one left out fails the build.
         */
        Builder capabilities(Set<Capability> offered);

        /**
         * Validates the settings and builds the client. Nothing connects until {@link JBurrowClient#start()}.
         *
         * @throws IllegalArgumentException naming the first setting that is missing or breaks its rule
         */
        JBurrowClient build();
    }
}
