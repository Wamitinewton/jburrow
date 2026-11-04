package io.jburrow.server.core.routing.domain;

import io.jburrow.protocol.control.SubdomainName;
import java.time.Instant;
import java.util.Objects;

/**
 * A name's claim in the route table: <em>active</em> while its tunnel exists, then <em>held</em> until
 * {@code heldUntil} for the principal that had it, so nobody else can take the URL in the seconds after its owner's
 * session drops.
 *
 * <p>Generic in the principal and the tunnel so the routing feature never depends on the feature that owns them; only
 * their {@code equals} matters here.
 *
 * @param tunnel required while active; {@code null} for a hold seeded from a previous server process, which had no
 *     tunnel in this one
 * @param heldUntil {@code null} while active
 */
public record Claim<P, T>(SubdomainName name, P principal, T tunnel, Instant heldUntil) {

    public Claim {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(principal, "principal");
        if (heldUntil == null) {
            Objects.requireNonNull(tunnel, "tunnel");
        }
    }

    public static <P, T> Claim<P, T> active(SubdomainName name, P principal, T tunnel) {
        return new Claim<>(name, principal, tunnel, null);
    }

    /** A hold with no tunnel behind it, carried over from a previous server process. */
    public static <P, T> Claim<P, T> seededHold(SubdomainName name, P principal, Instant until) {
        return new Claim<>(name, principal, null, Objects.requireNonNull(until, "until"));
    }

    public boolean active() {
        return heldUntil == null;
    }

    /** This claim, held from now on until {@code until}. */
    public Claim<P, T> heldUntil(Instant until) {
        return new Claim<>(name, principal, tunnel, Objects.requireNonNull(until, "until"));
    }

    public boolean expiredAt(Instant now) {
        return heldUntil != null && !now.isBefore(heldUntil);
    }

    /** Whether {@code claimant} may replace this claim at {@code now}: only a hold, and only its own or an expired one. */
    public boolean yieldsTo(P claimant, Instant now) {
        return !active() && (expiredAt(now) || principal.equals(claimant));
    }

    public boolean isActiveFor(T other) {
        return active() && tunnel.equals(other);
    }
}
