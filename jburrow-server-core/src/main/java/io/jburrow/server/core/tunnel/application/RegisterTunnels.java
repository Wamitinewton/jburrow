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
import io.jburrow.server.core.tunnel.application.port.LiveSessions;
import io.jburrow.server.core.tunnel.application.port.TunnelMetrics;
import io.jburrow.server.core.tunnel.application.port.TunnelMetrics.ClaimOutcome;
import io.jburrow.server.core.tunnel.application.port.TunnelMetrics.ResumeOutcome;
import io.jburrow.server.core.tunnel.domain.Principal;
import io.jburrow.server.core.tunnel.domain.ResumeTokenHash;
import io.jburrow.server.core.tunnel.domain.SessionId;
import io.jburrow.server.core.tunnel.domain.TunnelId;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;

/**
 * Checks a {@code REGISTER}, reserves its ports and claims its names, in the order of {@code docs/protocol.md} §8.4.
 * Blocks on the {@link AuthtokenVerifier}, so it runs on a virtual thread, never on an event loop.
 */
public final class RegisterTunnels {

    private final AuthtokenVerifier verifier;
    private final SessionAdmission admission;
    private final PortReservations<Principal> ports;
    private final SubdomainClaims<Principal, TunnelId> names;
    private final RegistrationPolicy policy;
    private final TunnelMetrics metrics;
    private final LiveSessions liveSessions;
    private final ResumeTokens resumeTokens;
    private final Duration replaceTimeout;

    /** Registration whose resume tokens never name a live session, for a server or a test without {@code resume}. */
    public RegisterTunnels(
            AuthtokenVerifier verifier,
            SessionAdmission admission,
            PortReservations<Principal> ports,
            SubdomainClaims<Principal, TunnelId> names,
            RegistrationPolicy policy,
            TunnelMetrics metrics) {
        this(
                verifier,
                admission,
                ports,
                names,
                policy,
                metrics,
                LiveSessions.none(),
                new ResumeTokens(),
                Duration.ZERO);
    }

    /**
     * @param liveSessions ends the session a resume token names (step 2a)
     * @param resumeTokens issues the token of each reply in a session with {@code resume}
     * @param replaceTimeout the longest step 2a waits for the session it ended to release its tunnels
     */
    public RegisterTunnels(
            AuthtokenVerifier verifier,
            SessionAdmission admission,
            PortReservations<Principal> ports,
            SubdomainClaims<Principal, TunnelId> names,
            RegistrationPolicy policy,
            TunnelMetrics metrics,
            LiveSessions liveSessions,
            ResumeTokens resumeTokens,
            Duration replaceTimeout) {
        this.verifier = Objects.requireNonNull(verifier, "verifier");
        this.admission = Objects.requireNonNull(admission, "admission");
        this.ports = Objects.requireNonNull(ports, "ports");
        this.names = Objects.requireNonNull(names, "names");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.liveSessions = Objects.requireNonNull(liveSessions, "liveSessions");
        this.resumeTokens = Objects.requireNonNull(resumeTokens, "resumeTokens");
        this.replaceTimeout = Objects.requireNonNull(replaceTimeout, "replaceTimeout");
        if (replaceTimeout.isNegative()) {
            throw new IllegalArgumentException("replaceTimeout must not be negative");
        }
    }

    public RegistrationPolicy policy() {
        return policy;
    }

    /**
     * Registers {@code request} for {@code session}, the connection holding {@code ticket}, in a session that has
     * {@code capabilities}. All or nothing: if a port can't be reserved or a name claimed, every port and name taken
     * before it is released, and the exception names the tunnel's {@code ref}. Ports are reserved before any name is
     * claimed.
     *
     * <p>With {@code resume}, a resume token in the request ends the live session it names before admission (step 2a),
     * which never refuses; and the registration carries a new token for the reply.
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
    public Registration execute(
            SessionId session, HandshakeTicket ticket, Register request, Set<Capability> capabilities) {
        Objects.requireNonNull(session, "session");
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
        boolean resume = capabilities.contains(Capability.RESUME);
        if (resume && request.resume() != null) {
            replaceStaleSession(request, principal, session);
        }
        admission.admit(ticket, principal);
        List<PlannedTunnel> tunnels = plan(request.tunnels(), principal);
        return new Registration(principal, tunnels, resume ? Optional.of(resumeTokens.issue()) : Optional.empty());
    }

    /**
     * Step 2a: the session the token names still counts against its owner's session limit, and holds the names and
     * ports the request asks for again, so it ends first. Its claims are then holds for the same principal, which steps
     * 4 and 5 grant like any other. The session making the request has no token of its own until its reply, so it can't
     * name itself.
     */
    private void replaceStaleSession(Register request, Principal principal, SessionId session) {
        boolean replaced =
                liveSessions.replace(ResumeTokenHash.of(request.resume()), principal, session, replaceTimeout);
        metrics.resumed(replaced ? ResumeOutcome.REPLACED : ResumeOutcome.NONE);
    }

    /** Puts every port the registration reserved and every name it claimed on hold for its principal. */
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
                    planned[i] = new PlannedTunnel.Tcp(request, reservePort(request, principal), TunnelId.random());
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

    private Reservation reservePort(TunnelRequest request, Principal principal) {
        OptionalInt requestedPort =
                request.remotePort() == null ? OptionalInt.empty() : OptionalInt.of(request.remotePort());
        try {
            return ports.reserve(requestedPort, principal);
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
