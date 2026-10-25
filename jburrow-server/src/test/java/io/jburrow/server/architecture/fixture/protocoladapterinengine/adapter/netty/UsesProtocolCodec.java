package io.jburrow.server.architecture.fixture.protocoladapterinengine.adapter.netty;

import io.jburrow.protocol.adapter.jackson.ControlJson;
import io.jburrow.protocol.adapter.netty.FrameCodec;

/** An engine adapter building the protocol's codec, as the edge does. Allowed: the protocol is no feature. */
public final class UsesProtocolCodec {

    public FrameCodec codec() {
        return new FrameCodec(FrameCodec.Role.SERVER, ControlJson.create());
    }
}
