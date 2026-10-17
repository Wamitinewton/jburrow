package io.jburrow.server.architecture.fixture.cycle.alpha.application.port;

import io.jburrow.server.architecture.fixture.cycle.beta.application.port.BetaPort;

public interface AlphaPort {

    BetaPort beta();
}
