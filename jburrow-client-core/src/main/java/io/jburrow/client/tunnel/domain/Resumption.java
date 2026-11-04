package io.jburrow.client.tunnel.domain;

import io.jburrow.protocol.control.RegisteredTunnel;
import io.jburrow.protocol.control.ResumeToken;
import io.jburrow.protocol.control.SubdomainName;
import io.jburrow.protocol.control.TunnelKind;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * What a reconnecting session asks for, from the session before it (ARCHITECTURE.md §Asking for the same names, phase
 * 4): the resume token its {@code REGISTER} reply carried, and for each tunnel whose name or port the server chose, the
 * one it got, so a reconnect keeps a random URL across a deploy. A tunnel whose name or port the user chose asks for it
 * as before.
 *
 * <p>Kept in memory only. {@link #toString()} never shows the token.
 */
public final class Resumption {

    /** Who chose the name or port a tunnel asks for. */
    public enum Pin {
        /** It asks for none: the server chooses. */
        NONE,
        /** The user asked for it. */
        USER_CHOSEN,
        /** The server chose it for an earlier session, and this one asks for it again. */
        SERVER_CHOSEN
    }

    private static final Resumption NONE = new Resumption(null, Map.of());

    private final ResumeToken token;
    /** By tunnel ref: the name or port the server chose, as a spec asks for it. */
    private final Map<String, TunnelSpec> pinned;

    private Resumption(ResumeToken token, Map<String, TunnelSpec> pinned) {
        this.token = token;
        this.pinned = Map.copyOf(pinned);
    }

    /** What the first session asks for: only what the user chose. */
    public static Resumption none() {
        return NONE;
    }

    /**
     * What follows an established session: its token, and the name or port of each of its tunnels that the user didn't
     * choose. A name that isn't the first label of a valid subdomain is not asked for again.
     *
     * @param requested the tunnels as the user asked for them
     * @param registered the tunnels of the session's {@code REGISTER} reply
     * @param token the reply's resume token, if the session had {@code resume}
     */
    public static Resumption after(
            List<TunnelSpec> requested, List<RegisteredTunnel> registered, Optional<ResumeToken> token) {
        Map<String, TunnelSpec> byRef = new HashMap<>();
        requested.forEach(spec -> byRef.put(spec.ref(), spec));
        Map<String, TunnelSpec> pinned = new HashMap<>();
        for (RegisteredTunnel tunnel : registered) {
            TunnelSpec spec = byRef.get(tunnel.ref());
            if (spec == null || userChose(spec) || spec.kind() != tunnel.kind()) {
                continue;
            }
            try {
                if (spec.kind() == TunnelKind.TCP) {
                    pinned.put(spec.ref(), spec.remotePort(tunnel.publicPort()));
                } else {
                    firstLabel(tunnel.publicHost()).ifPresent(name -> pinned.put(spec.ref(), spec.subdomain(name)));
                }
            } catch (IllegalArgumentException outsideTheRequestRules) {
                // A port REGISTER can't ask for: the server chooses again.
            }
        }
        return new Resumption(token.orElse(null), pinned);
    }

    /** The token to present in {@code REGISTER}, if the server lists {@code resume}. */
    public Optional<ResumeToken> token() {
        return Optional.ofNullable(token);
    }

    /** {@code spec} as this session asks for it: with the name or port the server chose before, if there is one. */
    public TunnelSpec apply(TunnelSpec spec) {
        Objects.requireNonNull(spec, "spec");
        return userChose(spec) ? spec : pinned.getOrDefault(spec.ref(), spec);
    }

    /** Who chose the name or port {@code spec} asks for in this session. */
    public Pin pin(TunnelSpec spec) {
        Objects.requireNonNull(spec, "spec");
        if (userChose(spec)) {
            return Pin.USER_CHOSEN;
        }
        return pinned.containsKey(spec.ref()) ? Pin.SERVER_CHOSEN : Pin.NONE;
    }

    /** The same, except that tunnel {@code ref} lets the server choose again. The token is kept. */
    public Resumption withoutPin(String ref) {
        Map<String, TunnelSpec> rest = new HashMap<>(pinned);
        rest.remove(Objects.requireNonNull(ref, "ref"));
        return new Resumption(token, rest);
    }

    @Override
    public String toString() {
        return "Resumption[token=" + (token == null ? "none" : token) + ", pinned=" + pinned.keySet() + "]";
    }

    private static boolean userChose(TunnelSpec spec) {
        return spec.kind() == TunnelKind.TCP
                ? spec.remotePort().isPresent()
                : spec.subdomain().isPresent();
    }

    private static Optional<SubdomainName> firstLabel(String host) {
        int dot = host.indexOf('.');
        return dot <= 0 ? Optional.empty() : SubdomainName.parse(host.substring(0, dot));
    }
}
