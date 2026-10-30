package io.jburrow.server.core.tunnel.adapter.netty.http;

import io.jburrow.protocol.control.BodyFraming;
import io.jburrow.protocol.control.ClientInfo;
import io.jburrow.protocol.control.HeaderFields;
import io.jburrow.protocol.control.Hello;
import io.jburrow.protocol.control.HttpRequestHead;
import io.jburrow.protocol.control.SubdomainName;
import io.jburrow.protocol.control.Windows;
import io.jburrow.protocol.wire.Capability;
import io.jburrow.server.core.tunnel.domain.Principal;
import io.jburrow.server.core.tunnel.domain.Session;
import io.jburrow.server.core.tunnel.domain.SessionId;
import io.jburrow.server.core.tunnel.domain.Tunnel;
import io.jburrow.server.core.tunnel.domain.TunnelId;
import java.util.List;
import java.util.Set;

/** Established sessions with {@code http} and one HTTP tunnel, for tests of the session's half of exchanges. */
final class HttpSessions {

    static final Windows SERVER_WINDOWS = new Windows(256 * 1024, 512 * 1024);
    static final Tunnel TUNNEL =
            Tunnel.http(TunnelId.random(), "default", new SubdomainName("quiet-otter"), "example.dev", "https", 443);
    static final HttpRequestHead UPLOAD = new HttpRequestHead(
            "POST", "/upload", HeaderFields.of("host", "quiet-otter.example.dev"), BodyFraming.CHUNKED);
    static final HttpRequestHead GET =
            new HttpRequestHead("GET", "/", HeaderFields.of("host", "quiet-otter.example.dev"), BodyFraming.EMPTY);

    private HttpSessions() {}

    /** A session whose client announced {@code client}, which is the credit this side starts with. */
    static Session established(Windows client) {
        Session session = new Session(SessionId.random(), 64, SERVER_WINDOWS, Set.of(Capability.HTTP));
        session.onHello(new Hello(
                new ClientInfo("jburrow-cli", "0.3.0", "linux", "x86_64"),
                List.of(Capability.HTTP.wireName()),
                client));
        session.onRegisterReceived();
        session.onEstablished(new Principal("alice"), List.of(TUNNEL));
        return session;
    }
}
