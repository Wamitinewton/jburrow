package io.jburrow.server.architecture.fixture.routing.intodomain.tunnel.application;

import io.jburrow.server.core.routing.application.port.RouteLookup;
import io.jburrow.server.core.routing.domain.Blocklist;

public class ChecksTheBlocklist {

    private RouteLookup<String> allowed;

    private Blocklist forbidden;
}
