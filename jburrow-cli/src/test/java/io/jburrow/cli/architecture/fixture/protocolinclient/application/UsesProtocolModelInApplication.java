package io.jburrow.cli.architecture.fixture.protocolinclient.application;

import io.jburrow.protocol.wire.ErrorCode;
import io.jburrow.protocol.wire.ProtocolException;

public class UsesProtocolModelInApplication {

    ProtocolException reject() {
        return ProtocolException.session(ErrorCode.PROTOCOL_ERROR, "Rejected.");
    }
}
