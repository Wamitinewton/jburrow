package io.jburrow.protocol.control;

/** A visitor's address as the server saw it: an IP literal (IPv6 without brackets) and a port. */
public record RemoteAddress(String address, int port) {

    public RemoteAddress {
        Fields.ipLiteral(address, "address");
        Fields.range(port, 0, Fields.MAX_PORT, "port");
    }
}
