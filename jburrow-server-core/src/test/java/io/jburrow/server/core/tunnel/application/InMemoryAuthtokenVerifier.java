package io.jburrow.server.core.tunnel.application;

import io.jburrow.server.core.tunnel.application.port.AuthtokenVerifier;
import io.jburrow.server.core.tunnel.domain.Principal;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** An {@link AuthtokenVerifier} over a map of tokens to principals, counting its calls. */
public final class InMemoryAuthtokenVerifier implements AuthtokenVerifier {

    private final Map<String, Principal> principals = new ConcurrentHashMap<>();
    private final AtomicInteger calls = new AtomicInteger();

    public InMemoryAuthtokenVerifier with(String authtoken, String principal) {
        principals.put(authtoken, new Principal(principal));
        return this;
    }

    @Override
    public Optional<Principal> verify(String authtoken) {
        calls.incrementAndGet();
        return Optional.ofNullable(principals.get(authtoken));
    }

    public int calls() {
        return calls.get();
    }
}
