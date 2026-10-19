package io.jburrow.protocol.control;

/** The client's identity in its {@code HELLO}. {@code os} and {@code arch} are optional and may be null. */
public record ClientInfo(String name, String version, String os, String arch) {

    public ClientInfo {
        Fields.matching(name, Fields.PEER_NAME, "name");
        Fields.matching(version, Fields.PEER_VERSION, "version");
        Fields.optionalMatching(os, Fields.PLATFORM, "os");
        Fields.optionalMatching(arch, Fields.PLATFORM, "arch");
    }
}
