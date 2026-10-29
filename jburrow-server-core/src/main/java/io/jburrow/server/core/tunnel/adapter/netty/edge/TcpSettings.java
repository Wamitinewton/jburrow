package io.jburrow.server.core.tunnel.adapter.netty.edge;

import java.util.Objects;

/**
 * Where tunnel listeners bind, the host their public address names, and how much each visitor may park in the server's
 * kernel.
 *
 * @param bindAddress an IP literal, never resolved
 * @param visitorReceiveBuffer {@code SO_RCVBUF} for visitor connections, in bytes, or {@link #KERNEL_SIZED}
 */
public record TcpSettings(String bindAddress, String publicHost, int visitorReceiveBuffer) {

    /** Leaves each visitor's receive buffer to the kernel, which grows it with the visitor's traffic. */
    public static final int KERNEL_SIZED = 0;

    public TcpSettings {
        Objects.requireNonNull(bindAddress, "bindAddress");
        Objects.requireNonNull(publicHost, "publicHost");
        if (visitorReceiveBuffer < 0) {
            throw new IllegalArgumentException("visitorReceiveBuffer must not be negative: " + visitorReceiveBuffer);
        }
    }
}
