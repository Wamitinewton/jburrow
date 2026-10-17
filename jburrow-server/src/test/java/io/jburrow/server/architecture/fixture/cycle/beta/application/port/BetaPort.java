package io.jburrow.server.architecture.fixture.cycle.beta.application.port;

import io.jburrow.server.architecture.fixture.cycle.alpha.application.port.AlphaPort;

public interface BetaPort {

    AlphaPort alpha();
}
