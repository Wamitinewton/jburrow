package io.jburrow.protocol.control;

/** The server's identity in its {@code HELLO}. */
public record ServerInfo(String name, String version) {

    public ServerInfo {
        Fields.matching(name, Fields.PEER_NAME, "name");
        Fields.matching(version, Fields.PEER_VERSION, "version");
    }
}
