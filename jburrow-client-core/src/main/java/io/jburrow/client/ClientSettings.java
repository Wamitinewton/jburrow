package io.jburrow.client;

import io.jburrow.client.tunnel.application.port.TunnelEvents;
import io.jburrow.client.tunnel.domain.TunnelSpec;
import io.jburrow.protocol.control.ClientInfo;
import io.jburrow.protocol.control.Register;
import io.jburrow.protocol.control.Windows;
import io.jburrow.protocol.wire.Capability;
import io.jburrow.protocol.wire.ProtocolException;
import io.jburrow.protocol.wire.ProtocolVersion;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.net.ssl.TrustManagerFactory;

/**
 * A client's validated settings, as {@link JBurrowClient.Builder#build()} produces them. Every rule is checked in the
 * constructor, and a broken one is an {@link IllegalArgumentException} naming the setting, never its value.
 *
 * <p>The URL rules ({@code docs/protocol.md} §3.1, §3.2): scheme {@code wss}, or {@code ws} only when the host is
 * {@code localhost} or a loopback IP literal ({@code 127.0.0.0/8} or {@code ::1}), decided on the literal text and
 * never by resolving a name; path {@code /v1/connect}; no user information or fragment. A query is dropped, and the
 * stored URL always carries its port.
 *
 * <p>{@link #toString()} never contains the authtoken.
 *
 * @param server the normalized connect URL
 * @param tunnels exactly one in this version
 * @param handshakeTimeout how long the server has, from the TCP connection opening, to complete TLS and the upgrade
 *     and to answer {@code HELLO} and {@code REGISTER}
 * @param windows this client's receive windows, announced in its {@code HELLO}: the most the server may send it on one
 *     stream, and on all streams together, before it returns credit
 */
public record ClientSettings(
        URI server,
        String authtoken,
        List<TunnelSpec> tunnels,
        TunnelEvents events,
        ClientInfo clientInfo,
        Optional<TrustManagerFactory> trustManager,
        Duration handshakeTimeout,
        Windows windows) {

    public static final String CONNECT_PATH = "/v1/connect";
    public static final Duration DEFAULT_HANDSHAKE_TIMEOUT = Duration.ofSeconds(10);
    /**
     * 256 KiB per stream, as the server's; 1 MiB for the session, more generous than the server's, since a developer's
     * machine runs one session and the server's memory doesn't depend on it.
     */
    public static final Windows DEFAULT_WINDOWS = new Windows(256 * 1024, 1024 * 1024);

    static final String DEFAULT_CLIENT_NAME = "jburrow-client-core";

    private static final Duration MAX_HANDSHAKE_TIMEOUT = Duration.ofMinutes(5);
    private static final int WSS_DEFAULT_PORT = 443;
    private static final int WS_DEFAULT_PORT = 80;
    private static final Pattern IPV4 = Pattern.compile("(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})");
    private static final Pattern PLATFORM_INVALID = Pattern.compile("[^a-z0-9_]");
    private static final int MAX_PLATFORM_LENGTH = 32;
    private static final String UNKNOWN_VERSION = "unknown";

    public ClientSettings {
        server = connectUri(server);
        if (authtoken == null) {
            throw new IllegalArgumentException("An authtoken is required");
        }
        Objects.requireNonNull(tunnels, "tunnels");
        tunnels = List.copyOf(tunnels);
        if (tunnels.size() != 1) {
            throw new IllegalArgumentException("Exactly one tunnel is required; this version opens one per client");
        }
        requireValidRegister(authtoken, tunnels);
        Objects.requireNonNull(events, "events");
        Objects.requireNonNull(clientInfo, "clientInfo");
        Objects.requireNonNull(trustManager, "trustManager");
        Objects.requireNonNull(handshakeTimeout, "handshakeTimeout");
        if (handshakeTimeout.isNegative()
                || handshakeTimeout.isZero()
                || handshakeTimeout.compareTo(MAX_HANDSHAKE_TIMEOUT) > 0) {
            throw new IllegalArgumentException("The handshake timeout must be positive and at most "
                    + MAX_HANDSHAKE_TIMEOUT.toMinutes() + " minutes");
        }
        Objects.requireNonNull(windows, "windows");
    }

    /**
     * The capabilities offered in {@code HELLO}: every one this client implements, whatever its tunnels need, so the
     * server's answer says what it supports (§4.2).
     */
    public Set<Capability> capabilities() {
        return ProtocolVersion.capabilities();
    }

    /** Whether the connection uses TLS: a {@code wss} URL. */
    public boolean tls() {
        return "wss".equals(server.getScheme());
    }

    /** The server's host as the URL names it, an IPv6 literal without brackets. */
    public String host() {
        return unbracketed(server.getHost());
    }

    public int port() {
        return server.getPort();
    }

    /** {@code <name>/<version> (<os>; <arch>)}, from the same values as {@code HELLO} (§3.3). */
    public String userAgent() {
        String agent = clientInfo.name() + "/" + clientInfo.version();
        if (clientInfo.os() != null && clientInfo.arch() != null) {
            agent += " (" + clientInfo.os() + "; " + clientInfo.arch() + ")";
        }
        return agent;
    }

    @Override
    public String toString() {
        return "ClientSettings[server=" + server + ", authtoken=***, tunnels=" + tunnels + ", clientInfo="
                + clientInfo + ", trustManager=" + (trustManager.isPresent() ? "custom" : "system")
                + ", handshakeTimeout=" + handshakeTimeout + ", windows=" + windows + "]";
    }

    /**
     * The client identity for {@code HELLO}, with this machine's operating system and processor architecture.
     *
     * @throws IllegalArgumentException if the name or version breaks the protocol's rule for them
     */
    static ClientInfo clientInfo(String name, String version) {
        try {
            return new ClientInfo(
                    name,
                    version,
                    platform(normalizeOs(System.getProperty("os.name", ""))),
                    platform(normalizeArch(System.getProperty("os.arch", ""))));
        } catch (ProtocolException e) {
            throw new IllegalArgumentException("Client " + e.getMessage(), e);
        }
    }

    /** This library's version, from the build, or {@code unknown} when run without a Maven build. */
    static String defaultClientVersion() {
        Properties build = new Properties();
        try (InputStream in = ClientSettings.class.getResourceAsStream("client.properties")) {
            if (in != null) {
                build.load(in);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read client.properties", e);
        }
        String version = build.getProperty("version");
        return version == null || version.isBlank() || version.startsWith("@") ? UNKNOWN_VERSION : version;
    }

    private static URI connectUri(URI uri) {
        if (uri == null) {
            throw new IllegalArgumentException("A server URL is required");
        }
        String scheme = uri.getScheme() == null ? null : uri.getScheme().toLowerCase(Locale.ROOT);
        boolean tls;
        if ("wss".equals(scheme)) {
            tls = true;
        } else if ("ws".equals(scheme)) {
            tls = false;
        } else {
            throw new IllegalArgumentException("The server URL must start with wss:// (or ws:// to this machine)");
        }
        if (uri.getRawUserInfo() != null) {
            throw new IllegalArgumentException("The server URL must not contain user information");
        }
        if (uri.getRawFragment() != null) {
            throw new IllegalArgumentException("The server URL must not contain a fragment");
        }
        String host = uri.getHost();
        if (host == null || host.isEmpty()) {
            throw new IllegalArgumentException("The server URL needs a host");
        }
        if (!CONNECT_PATH.equals(uri.getRawPath())) {
            throw new IllegalArgumentException("The server URL's path must be " + CONNECT_PATH);
        }
        if (!tls && !isLoopback(host)) {
            throw new IllegalArgumentException("ws:// sends the authtoken unencrypted, so it is only allowed to this"
                    + " machine (localhost, 127.0.0.0/8 or ::1); use wss://");
        }
        int port = uri.getPort() == -1 ? (tls ? WSS_DEFAULT_PORT : WS_DEFAULT_PORT) : uri.getPort();
        try {
            return new URI(scheme, null, host, port, CONNECT_PATH, null, null);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("The server URL is not valid", e);
        }
    }

    /** On the literal text alone: a name other than {@code localhost} is never resolved to decide. */
    static boolean isLoopback(String uriHost) {
        String host = unbracketed(uriHost);
        if (host.equalsIgnoreCase("localhost")) {
            return true;
        }
        Matcher ipv4 = IPV4.matcher(host);
        if (ipv4.matches()) {
            for (int group = 1; group <= 4; group++) {
                if (Integer.parseInt(ipv4.group(group)) > 255) {
                    return false;
                }
            }
            return Integer.parseInt(ipv4.group(1)) == 127;
        }
        if (host.indexOf(':') >= 0) {
            try {
                InetAddress address = InetAddress.getByName(host);
                return address instanceof Inet6Address && address.isLoopbackAddress();
            } catch (UnknownHostException e) {
                return false;
            }
        }
        return false;
    }

    /** {@code REGISTER}'s own field rules, so a bad token or tunnel fails here rather than at the server. */
    private static void requireValidRegister(String authtoken, List<TunnelSpec> tunnels) {
        try {
            new Register(authtoken, tunnels.stream().map(TunnelSpec::toRequest).toList());
        } catch (ProtocolException e) {
            throw new IllegalArgumentException("Register " + e.getMessage(), e);
        }
    }

    private static String unbracketed(String host) {
        return host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;
    }

    private static String normalizeOs(String osName) {
        String os = osName.toLowerCase(Locale.ROOT);
        if (os.startsWith("linux")) {
            return "linux";
        }
        if (os.startsWith("mac") || os.startsWith("darwin")) {
            return "macos";
        }
        if (os.startsWith("windows")) {
            return "windows";
        }
        return os;
    }

    private static String normalizeArch(String osArch) {
        String arch = osArch.toLowerCase(Locale.ROOT);
        return switch (arch) {
            case "x86_64", "amd64" -> "amd64";
            case "aarch64", "arm64" -> "arm64";
            default -> arch;
        };
    }

    /** A value for {@code client.os} or {@code client.arch}, or null when nothing usable is left. */
    private static String platform(String value) {
        String cleaned = PLATFORM_INVALID.matcher(value).replaceAll("_");
        if (cleaned.length() > MAX_PLATFORM_LENGTH) {
            cleaned = cleaned.substring(0, MAX_PLATFORM_LENGTH);
        }
        return cleaned.isEmpty() ? null : cleaned;
    }
}
