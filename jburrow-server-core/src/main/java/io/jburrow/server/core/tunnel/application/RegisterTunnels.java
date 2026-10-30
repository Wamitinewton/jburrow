package io.jburrow.server.core.tunnel.application;

import io.jburrow.protocol.control.Register;
import io.jburrow.protocol.control.SubdomainName;
import io.jburrow.protocol.control.TunnelKind;
import io.jburrow.protocol.control.TunnelRequest;
import io.jburrow.protocol.wire.Capability;
import io.jburrow.protocol.wire.ErrorCode;
import io.jburrow.protocol.wire.ProtocolException;
import io.jburrow.server.core.portpool.application.port.PortReservations;
import io.jburrow.server.core.portpool.application.port.Reservation;
import io.jburrow.server.core.routing.application.port.ClaimRefused;
import io.jburrow.server.core.routing.application.port.SubdomainClaims;
import io.jburrow.server.core.tunnel.application.port.AuthtokenVerifier;
import io.jburrow.server.core.tunnel.application.port.TunnelMetrics;
import io.jburrow.server.core.tunnel.application.port.TunnelMetrics.ClaimOutcome;
import io.jburrow.server.core.tunnel.domain.Principal;
import io.jburrow.server.core.tunnel.domain.TunnelId;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.Set;

/**
 * Checks a {@code REGISTER}, reserves its ports and claims its names, in the order of {@code docs/protocol.md} §8.4.
 * Blocks on the {@link AuthtokenVerifier}, so it runs on a virtual thread, never on an event loop.
 */
public final class RegisterTunnels {

    private final AuthtokenVerifier verifier;
    private final SessionAdmission admission;
    private final PortReservations ports;
    private final SubdomainClaims<Principal, TunnelId> names;
    private final RegistrationPolicy policy;
    private final TunnelMetrics metrics;

    public RegisterTunnels(
            AuthtokenVerifier verifier,
            SessionAdmission admission,
            PortReservations ports,
            SubdomainClaims<Principal, TunnelId> names,
            RegistrationPolicy policy,
            TunnelMetrics metrics) {
        this.verifier = Objects.requireNonNull(verifier, "verifier");
        this.admission = Objects.requireNonNull(admission, "admission");
        this.ports = Objects.requireNonNull(ports, "ports");
        this.names = Objects.requireNonNull(names, "names");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
    }

    public RegistrationPolicy policy() {
        return policy;
    }

    /**
     * Registers {@code request} for the connection holding {@code ticket}, in a session that has {@code capabilities}.
     * All or nothing: if a port can't be reserved or a name claimed, every port and name taken before it is released,
     * and the exception names the tunnel's {@code ref}. Ports are reserved before any name is claimed.
     *
     * <p>The admission of step 3 is not undone here when a later step fails. A refusal always closes the connection,
     * and the edge releases the ticket when the connection closes, whatever state it reached. Keeping that rule in one
     * place means a ticket is freed exactly once however registration ends.
     *
     * @throws ProtocolException {@link ErrorCode#VALIDATION_FAILED}, {@link ErrorCode#TUNNEL_LIMIT_EXCEEDED},
     *     {@link ErrorCode#AUTHTOKEN_INVALID}, {@link ErrorCode#SESSION_LIMIT_EXCEEDED},
     *     {@link ErrorCode#PORT_UNAVAILABLE}, {@link ErrorCode#PORT_POOL_EXHAUSTED},
     *     {@link ErrorCode#SUBDOMAIN_FORBIDDEN} or {@link ErrorCode#SUBDOMAIN_UNAVAILABLE}, the first check that fails
     */
    public Registration execute(HandshakeTicket ticket, Register request, Set<Capability> capabilities) {
        Objects.requireNonNull(ticket, "ticket");
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(capabilities, "capabilities");
        requireKindsNegotiated(request.tunnels(), capabilities);
        if (request.tunnels().size() > policy.maxTunnels()) {
            throw ProtocolException.session(
                    ErrorCode.TUNNEL_LIMIT_EXCEEDED,
                    "This server allows at most " + policy.maxTunnels() + " tunnels per session.");
        }
        Principal principal = verifier.verify(request.authtoken())
                .map(Principal::new)
                .orElseThrow(
                        () -> ProtocolException.session(ErrorCode.AUTHTOKEN_INVALID, "The authtoken is not valid."));
        admission.admit(ticket, principal);
        return new Registration(principal, plan(request.tunnels(), principal));
    }

    /** Returns every port the registration reserved to the pool, and puts every name it claimed on hold. */
    public void release(Registration registration) {
        releaseAll(registration.tunnels());
    }

    /**
     * A kind the session hasn't negotiated is as unknown as any other value ({@code docs/protocol.md} §4.3), so it
     * breaks a field rule and fails before every check of §8.4. The session's codec refuses it first on the wire; this
     * keeps the rule true of the use case on its own, whatever decoded the request.
     */
    private static void requireKindsNegotiated(List<TunnelRequest> requests, Set<Capability> capabilities) {
        for (int i = 0; i < requests.size(); i++) {
            TunnelKind kind = requests.get(i).kind();
            if (kind.capability().isPresent()
                    && !capabilities.contains(kind.capability().get())) {
                List<String> known = Arrays.stream(TunnelKind.values())
                        .filter(candidate -> candidate
                                .capability()
                                .map(capabilities::contains)
                                .orElse(true))
                        .map(TunnelKind::wireName)
                        .toList();
                throw ProtocolException.session(
                        ErrorCode.VALIDATION_FAILED, "tunnels[" + i + "].kind must be one of " + known);
            }
        }
    }

    /** Ports for the TCP entries, then names for the HTTP entries, each in request order; the result in request order. */
    private List<PlannedTunnel> plan(List<TunnelRequest> requests, Principal principal) {
        PlannedTunnel[] planned = new PlannedTunnel[requests.size()];
        try {
            for (int i = 0; i < requests.size(); i++) {
                TunnelRequest request = requests.get(i);
                if (request.kind() == TunnelKind.TCP) {
                    planned[i] = new PlannedTunnel.Tcp(request, reservePort(request), TunnelId.random());
                }
            }
            for (int i = 0; i < requests.size(); i++) {
                TunnelRequest request = requests.get(i);
                if (request.kind() == TunnelKind.HTTP) {
                    TunnelId id = TunnelId.random();
                    planned[i] = new PlannedTunnel.Http(request, claimName(request, principal, id), id);
                }
            }
        } catch (RuntimeException failed) {
            releaseAll(Arrays.stream(planned).filter(Objects::nonNull).toList());
            throw failed;
        }
        return List.of(planned);
    }

    private Reservation reservePort(TunnelRequest request) {
        OptionalInt requestedPort =
                request.remotePort() == null ? OptionalInt.empty() : OptionalInt.of(request.remotePort());
        try {
            return ports.reserve(requestedPort);
        } catch (ProtocolException refused) {
            throw refused.forTunnel(request.ref());
        }
    }

    private SubdomainName claimName(TunnelRequest request, Principal principal, TunnelId id) {
        if (request.subdomain() == null) {
            SubdomainName name = names.claimRandom(principal, id);
            metrics.subdomainClaimed(ClaimOutcome.RANDOM);
            return name;
        }
        try {
            SubdomainName name = names.claim(new SubdomainName(request.subdomain()), principal, id);
            metrics.subdomainClaimed(ClaimOutcome.REQUESTED);
            return name;
        } catch (ClaimRefused refused) {
            ProtocolException error =
                    switch (refused.reason()) {
                        case FORBIDDEN -> {
                            metrics.subdomainClaimed(ClaimOutcome.FORBIDDEN);
                            yield ProtocolException.session(
                                    ErrorCode.SUBDOMAIN_FORBIDDEN,
                                    "The subdomain is reserved or blocked on this server.");
                        }
                        case UNAVAILABLE -> {
                            metrics.subdomainClaimed(ClaimOutcome.UNAVAILABLE);
                            yield ProtocolException.session(
                                    ErrorCode.SUBDOMAIN_UNAVAILABLE,
                                    "The subdomain is in use or held for someone else.");
                        }
                    };
            throw error.forTunnel(request.ref());
        }
    }

    private void releaseAll(List<PlannedTunnel> planned) {
        for (PlannedTunnel tunnel : planned) {
            switch (tunnel) {
                case PlannedTunnel.Tcp tcp -> ports.release(tcp.reservation());
                case PlannedTunnel.Http http -> names.release(http.name(), http.id());
            }
        }
    }
}
