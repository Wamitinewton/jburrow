package io.jburrow.server.architecture.fixture.protocolincore.application;

import io.jburrow.protocol.wire.ErrorCode;
import io.jburrow.protocol.wire.ProtocolException;

public class UsesProtocolModelInApplication {

    ProtocolException reject() {
        return ProtocolException.session(ErrorCode.PROTOCOL_ERROR, "Rejected.");
    }
}
