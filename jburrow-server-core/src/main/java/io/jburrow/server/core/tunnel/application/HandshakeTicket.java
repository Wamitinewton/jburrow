package io.jburrow.server.core.tunnel.application;

import io.jburrow.server.core.tunnel.domain.Principal;
import java.util.concurrent.atomic.AtomicReference;

/**
 * One connection's place in {@link SessionAdmission}: a handshake slot, then a session counted for its principal, then
 * nothing once released. Only {@link SessionAdmission} creates and moves it, and every move is a compare-and-set, so a
 * release racing an admission from another thread is counted exactly once.
 */
public final class HandshakeTicket {

    /** Where a ticket is. */
    sealed interface Stage {}

    record Handshaking() implements Stage {}

    record Admitted(Principal principal) implements Stage {}

    record Released() implements Stage {}

    static final Stage HANDSHAKING = new Handshaking();
    static final Stage RELEASED = new Released();

    private final SessionAdmission owner;
    private final AtomicReference<Stage> stage = new AtomicReference<>(HANDSHAKING);

    HandshakeTicket(SessionAdmission owner) {
        this.owner = owner;
    }

    SessionAdmission owner() {
        return owner;
    }

    Stage stage() {
        return stage.get();
    }

    boolean admit(Principal principal) {
        return stage.compareAndSet(HANDSHAKING, new Admitted(principal));
    }

    Stage release() {
        return stage.getAndSet(RELEASED);
    }
}
