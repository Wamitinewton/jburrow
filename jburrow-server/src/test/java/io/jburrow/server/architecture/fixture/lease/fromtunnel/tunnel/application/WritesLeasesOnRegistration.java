package io.jburrow.server.architecture.fixture.lease.fromtunnel.tunnel.application;

import io.jburrow.server.core.lease.application.port.LeaseStore;

/** Reaches only a port, which the ports rule allows; the lease rule still refuses it. */
public class WritesLeasesOnRegistration {

    private LeaseStore forbidden;
}
