package io.jburrow.server.core.tunnel.adapter.netty.control;

import io.jburrow.server.core.tunnel.application.HandshakeTicket;
import io.jburrow.server.core.tunnel.application.Registration;
import io.jburrow.server.core.tunnel.application.port.TunnelMetrics.SessionEnd;
import io.jburrow.server.core.tunnel.domain.Session;
import io.netty.channel.Channel;
import io.netty.util.concurrent.ScheduledFuture;
import java.net.InetAddress;
import java.util.Objects;
import java.util.Optional;

/**
 * Everything one control session holds: the {@link Session}, its connection, its handshake ticket, its registration
 * once there is one, its streams, its timers and its counters, and the {@link SessionCloser} that ends it.
 *
 * <p>Not thread-safe. Confined to the event loop of {@link #channel()}, except {@link #session()}'s id and
 * {@link #channel()} itself, which never change and may be read from anywhere.
 */
public final class SessionContext {

    private final Session session;
    private final Channel channel;
    private final HandshakeTicket ticket;
    private final InetAddress clientAddress;
    private final long startedNanos;
    private final SessionCloser closer;

    private Registration registration;
    private SessionEnd end;
    private LivenessHandler liveness;
    private SessionStreams streams;
    private ScheduledFuture<?> handshakeDeadline;
    private ScheduledFuture<?> forceClose;
    private long streamsServed;
    private long bytesIn;
    private long bytesOut;

    public SessionContext(
            Session session,
            Channel channel,
            HandshakeTicket ticket,
            InetAddress clientAddress,
            long startedNanos,
            SessionCloser closer) {
        this.session = Objects.requireNonNull(session, "session");
        this.channel = Objects.requireNonNull(channel, "channel");
        this.ticket = Objects.requireNonNull(ticket, "ticket");
        this.clientAddress = Objects.requireNonNull(clientAddress, "clientAddress");
        this.startedNanos = startedNanos;
        this.closer = Objects.requireNonNull(closer, "closer");
    }

    /** Ends this session; every path that ends a session goes through it. */
    public SessionCloser closer() {
        return closer;
    }

    public Session session() {
        return session;
    }

    public Channel channel() {
        return channel;
    }

    /** Released by the edge when the channel closes, never by the session. */
    public HandshakeTicket ticket() {
        return ticket;
    }

    public InetAddress clientAddress() {
        return clientAddress;
    }

    /** {@code System.nanoTime()} when the connection upgraded. */
    public long startedNanos() {
        return startedNanos;
    }

    /** The accepted registration, from the moment its result reaches the event loop until its ports are released. */
    public Optional<Registration> registration() {
        return Optional.ofNullable(registration);
    }

    public void registration(Registration registration) {
        this.registration = Objects.requireNonNull(registration, "registration");
    }

    /** Hands the registration over for release, so it is released exactly once. */
    Optional<Registration> takeRegistration() {
        Optional<Registration> taken = Optional.ofNullable(registration);
        registration = null;
        return taken;
    }

    /** Keeps the session's liveness clock, which starts when the server's {@code HELLO} is sent. */
    LivenessHandler liveness() {
        if (liveness == null) {
            throw new IllegalStateException("The session's pipeline is not built");
        }
        return liveness;
    }

    void liveness(LivenessHandler liveness) {
        this.liveness = Objects.requireNonNull(liveness, "liveness");
    }

    /** The session's streams, once its tunnel listeners have started binding. */
    public Optional<SessionStreams> streams() {
        return Optional.ofNullable(streams);
    }

    /** Set once, by the tunnel listeners. */
    public void streams(SessionStreams streams) {
        if (this.streams != null) {
            throw new IllegalStateException("Session " + session.id() + " already has its streams");
        }
        this.streams = Objects.requireNonNull(streams, "streams");
    }

    /** Why the session closed without a session error; empty if it hasn't, or did with one. */
    Optional<SessionEnd> end() {
        return Optional.ofNullable(end);
    }

    /** Records the first reason only. */
    void end(SessionEnd end) {
        if (this.end == null) {
            this.end = Objects.requireNonNull(end, "end");
        }
    }

    void handshakeDeadline(ScheduledFuture<?> deadline) {
        this.handshakeDeadline = deadline;
    }

    void cancelHandshakeDeadline() {
        if (handshakeDeadline != null) {
            handshakeDeadline.cancel(false);
            handshakeDeadline = null;
        }
    }

    void forceClose(ScheduledFuture<?> forceClose) {
        this.forceClose = forceClose;
    }

    void cancelForceClose() {
        if (forceClose != null) {
            forceClose.cancel(false);
            forceClose = null;
        }
    }

    public void streamServed(long bytesIn, long bytesOut) {
        streamsServed++;
        this.bytesIn += bytesIn;
        this.bytesOut += bytesOut;
    }

    public long streamsServed() {
        return streamsServed;
    }

    /** Payload bytes from visitors to the client, over every stream that has ended. */
    public long bytesIn() {
        return bytesIn;
    }

    /** Payload bytes from the client to visitors, over every stream that has ended. */
    public long bytesOut() {
        return bytesOut;
    }
}
