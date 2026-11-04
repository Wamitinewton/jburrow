package io.jburrow.server.architecture.fixture.lease.intotunnel.lease.application;

import io.jburrow.server.core.routing.application.port.ClaimSnapshots;
import io.jburrow.server.core.tunnel.domain.Principal;

public class SeedsForAPrincipal {

    private ClaimSnapshots<Principal> allowed;

    private Principal forbidden;
}
