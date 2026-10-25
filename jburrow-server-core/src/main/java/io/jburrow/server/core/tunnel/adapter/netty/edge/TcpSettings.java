package io.jburrow.server.core.tunnel.adapter.netty.edge;

import java.util.Objects;

/**
 * Where tunnel listeners bind and the host their public address names.
 *
 * @param bindAddress an IP literal, never resolved
 */
public record TcpSettings(String bindAddress, String publicHost) {

    public TcpSettings {
        Objects.requireNonNull(bindAddress, "bindAddress");
        Objects.requireNonNull(publicHost, "publicHost");
    }
}
