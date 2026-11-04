package io.jburrow.server.core.routing.application;

import io.jburrow.protocol.control.SubdomainName;
import io.jburrow.server.core.routing.application.port.ClaimRefused;
import io.jburrow.server.core.routing.application.port.ClaimSnapshot;
import io.jburrow.server.core.routing.application.port.ClaimSnapshots;
import io.jburrow.server.core.routing.application.port.RouteLookup;
import io.jburrow.server.core.routing.application.port.SubdomainClaims;
import io.jburrow.server.core.routing.domain.Blocklist;
import io.jburrow.server.core.routing.domain.Claim;
import io.jburrow.server.core.routing.domain.NameGenerator;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The in-memory route table: every claimed name and the tunnel it routes to.
 *
 * <p>Global state behind its own small synchronisation (ADR-0008): a {@link ConcurrentHashMap} changed by an atomic
 * per-name compute, so a lookup is one lock-free read and claims on different names never contend.
 *
 * <p>An expired hold is removed when its name is next claimed or released, and by a sweep that runs at most once per
 * {@link #SWEEP_INTERVAL} on the thread of a {@link #release}, so the table doesn't grow with names nobody uses again.
 * There is no scheduler.
 *
 * <p>{@link #version} counts every change to the table, made inside the same atomic compute as the change, so a reader
 * that sees the same value twice knows nothing was claimed, released, seeded or expired in between.
 */
public final class SubdomainRoutes<P, T> implements SubdomainClaims<P, T>, RouteLookup<T>, ClaimSnapshots<P> {

    static final int MAX_RANDOM_DRAWS = 8;
    static final Duration SWEEP_INTERVAL = Duration.ofMinutes(1);

    private final Map<SubdomainName, Claim<P, T>> claims = new ConcurrentHashMap<>();
    private final Blocklist blocklist;
    private final NameGenerator names;
    private final Duration hold;
    private final InstantSource clock;
    private final AtomicLong nextSweepMillis;
    private final AtomicLong version = new AtomicLong();

    /** @param hold how long a released name stays held for its principal; zero turns holds off */
    public SubdomainRoutes(Blocklist blocklist, NameGenerator names, Duration hold, InstantSource clock) {
        this.blocklist = Objects.requireNonNull(blocklist, "blocklist");
        this.names = Objects.requireNonNull(names, "names");
        this.hold = Objects.requireNonNull(hold, "hold");
        if (hold.isNegative()) {
            throw new IllegalArgumentException("hold must not be negative: " + hold);
        }
        this.clock = Objects.requireNonNull(clock, "clock");
        this.nextSweepMillis = new AtomicLong(clock.millis() + SWEEP_INTERVAL.toMillis());
    }

    @Override
    public SubdomainName claimRandom(P principal, T tunnel) {
        Objects.requireNonNull(principal, "principal");
        Objects.requireNonNull(tunnel, "tunnel");
        for (int draw = 0; draw < MAX_RANDOM_DRAWS; draw++) {
            SubdomainName name = names.next();
            if (blocklist.allows(name) && claims.putIfAbsent(name, Claim.active(name, principal, tunnel)) == null) {
                version.incrementAndGet();
                return name;
            }
        }
        throw new IllegalStateException(MAX_RANDOM_DRAWS + " random names in a row were taken");
    }

    @Override
    public SubdomainName claim(SubdomainName name, P principal, T tunnel) {
        Objects.requireNonNull(name, "name");
        Claim<P, T> wanted = Claim.active(name, principal, tunnel);
        if (!blocklist.allows(name)) {
            throw new ClaimRefused(ClaimRefused.Reason.FORBIDDEN);
        }
        Instant now = clock.instant();
        Claim<P, T> result = claims.compute(name, (key, existing) -> {
            if (existing == null || existing.yieldsTo(principal, now)) {
                version.incrementAndGet();
                return wanted;
            }
            return existing;
        });
        if (result != wanted) {
            throw new ClaimRefused(ClaimRefused.Reason.UNAVAILABLE);
        }
        return name;
    }

    @Override
    public void release(SubdomainName name, T tunnel) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(tunnel, "tunnel");
        Instant now = clock.instant();
        claims.computeIfPresent(name, (key, existing) -> {
            if (existing.isActiveFor(tunnel)) {
                version.incrementAndGet();
                return hold.isZero() ? null : existing.heldUntil(now.plus(hold));
            }
            if (existing.expiredAt(now)) {
                version.incrementAndGet();
                return null;
            }
            return existing;
        });
        sweepIfDue(now);
    }

    @Override
    public Optional<T> find(SubdomainName name) {
        Claim<P, T> claim = claims.get(name);
        return claim != null && claim.active() ? Optional.of(claim.tunnel()) : Optional.empty();
    }

    @Override
    public List<ClaimSnapshot<P>> snapshot(Instant now) {
        Objects.requireNonNull(now, "now");
        List<ClaimSnapshot<P>> snapshot = new ArrayList<>();
        claims.forEach((name, claim) -> {
            if (!claim.expiredAt(now)) {
                snapshot.add(new ClaimSnapshot<>(name, claim.principal(), claim.heldUntil()));
            }
        });
        return snapshot;
    }

    @Override
    public boolean seedHold(SubdomainName name, P principal, Instant until) {
        Objects.requireNonNull(name, "name");
        Claim<P, T> seeded = Claim.seededHold(name, principal, until);
        Instant now = clock.instant();
        if (!until.isAfter(now)) {
            return false;
        }
        Claim<P, T> result = claims.compute(name, (key, existing) -> {
            if (existing == null || existing.expiredAt(now)) {
                version.incrementAndGet();
                return seeded;
            }
            return existing;
        });
        return result == seeded;
    }

    @Override
    public long version() {
        return version.get();
    }

    /** How many names have a claim, active or held, expired or not. */
    int size() {
        return claims.size();
    }

    private void sweepIfDue(Instant now) {
        long due = nextSweepMillis.get();
        long nowMillis = now.toEpochMilli();
        if (nowMillis < due || !nextSweepMillis.compareAndSet(due, nowMillis + SWEEP_INTERVAL.toMillis())) {
            return;
        }
        claims.forEach((name, claim) -> {
            if (claim.expiredAt(now) && claims.remove(name, claim)) {
                version.incrementAndGet();
            }
        });
    }
}
