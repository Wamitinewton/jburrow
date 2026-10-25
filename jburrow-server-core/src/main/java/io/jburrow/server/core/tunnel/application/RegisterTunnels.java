package io.jburrow.server.core.tunnel.application;

import io.jburrow.protocol.control.Register;
import io.jburrow.protocol.control.TunnelRequest;
import io.jburrow.protocol.wire.ErrorCode;
import io.jburrow.protocol.wire.ProtocolException;
import io.jburrow.server.core.portpool.application.port.PortReservations;
import io.jburrow.server.core.portpool.application.port.Reservation;
import io.jburrow.server.core.tunnel.application.port.AuthtokenVerifier;
import io.jburrow.server.core.tunnel.domain.Principal;
import io.jburrow.server.core.tunnel.domain.TunnelId;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.OptionalInt;

/**
 * Checks a {@code REGISTER} and reserves its ports, in the order of {@code docs/protocol.md} §8.4. Blocks on the
 * {@link AuthtokenVerifier}, so it runs on a virtual thread, never on an event loop.
 */
public final class RegisterTunnels {

    private final AuthtokenVerifier verifier;
    private final SessionAdmission admission;
    private final PortReservations ports;
    private final RegistrationPolicy policy;

    public RegisterTunnels(
            AuthtokenVerifier verifier, SessionAdmission admission, PortReservations ports, RegistrationPolicy policy) {
        this.verifier = Objects.requireNonNull(verifier, "verifier");
        this.admission = Objects.requireNonNull(admission, "admission");
        this.ports = Objects.requireNonNull(ports, "ports");
        this.policy = Objects.requireNonNull(policy, "policy");
    }

    public RegistrationPolicy policy() {
        return policy;
    }

    /**
     * Registers {@code request} for the connection holding {@code ticket}. All or nothing: if a port can't be
     * reserved, every port reserved before it is released, and the exception names the tunnel's {@code ref}.
     *
     * <p>The admission of step 3 is not undone here when a later step fails. A refusal always closes the connection,
     * and the edge releases the ticket when the connection closes, whatever state it reached. Keeping that rule in one
     * place means a ticket is freed exactly once however registration ends.
     *
     * @throws ProtocolException {@link ErrorCode#TUNNEL_LIMIT_EXCEEDED}, {@link ErrorCode#AUTHTOKEN_INVALID},
     *     {@link ErrorCode#SESSION_LIMIT_EXCEEDED}, {@link ErrorCode#PORT_UNAVAILABLE} or
     *     {@link ErrorCode#PORT_POOL_EXHAUSTED}, the first check that fails
     */
    public Registration execute(HandshakeTicket ticket, Register request) {
        Objects.requireNonNull(ticket, "ticket");
        Objects.requireNonNull(request, "request");
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
        return new Registration(principal, reservePorts(request.tunnels()));
    }

    /** Returns every port the registration reserved to the pool. */
    public void release(Registration registration) {
        releaseAll(registration.tunnels());
    }

    private List<PlannedTunnel> reservePorts(List<TunnelRequest> requests) {
        List<PlannedTunnel> planned = new ArrayList<>(requests.size());
        for (TunnelRequest request : requests) {
            OptionalInt requestedPort =
                    request.remotePort() == null ? OptionalInt.empty() : OptionalInt.of(request.remotePort());
            Reservation reservation;
            try {
                reservation = ports.reserve(requestedPort);
            } catch (ProtocolException refused) {
                releaseAll(planned);
                throw refused.forTunnel(request.ref());
            } catch (RuntimeException unexpected) {
                releaseAll(planned);
                throw unexpected;
            }
            planned.add(new PlannedTunnel(request, reservation, TunnelId.random()));
        }
        return planned;
    }

    private void releaseAll(List<PlannedTunnel> planned) {
        for (PlannedTunnel tunnel : planned) {
            ports.release(tunnel.reservation());
        }
    }
}
