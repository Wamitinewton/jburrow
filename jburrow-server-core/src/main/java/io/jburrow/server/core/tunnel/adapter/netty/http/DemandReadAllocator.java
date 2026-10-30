package io.jburrow.server.core.tunnel.adapter.netty.http;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.channel.Channel;
import io.netty.channel.ChannelConfig;
import io.netty.channel.RecvByteBufAllocator;
import io.netty.util.UncheckedBooleanSupplier;
import java.util.Objects;

/**
 * Sizes the reads of an edge connection in its request phase ({@code docs/phase-3-http-tunnels/ARCHITECTURE.md}
 * §Visitor to client: demand reads). A request body is read one demand at a time: {@link #demand} sets the size of the
 * next read to exactly the bytes the exchange has reserved, and the read after it falls back to {@link #HEAD_READ}.
 * Heads are read in {@link #HEAD_READ} chunks, so what a head's read takes of the body behind it is under 4 KiB.
 *
 * <p>Exactly one read per turn, and no adaptive guessing: a guess that grew would read past a reservation. Netty's epoll
 * transport still drains a connection that sent {@code FIN} in one turn, whatever this says; the session side holds
 * what such a read takes past its reservation.
 *
 * <p>Installed when the connection is accepted, before its first read: Netty makes a channel's read handle once, on
 * that read, and keeps it, so an allocator set later would never be asked. A connection that becomes a control session
 * is switched to {@link #toSessionReads session reads}, which are the channel's original allocator's.
 *
 * <p>Confined to the connection's event loop.
 */
public final class DemandReadAllocator implements RecvByteBufAllocator {

    static final int HEAD_READ = 4_096;

    private final RecvByteBufAllocator sessionReads;
    private boolean requestPhase = true;
    private int demand;

    private DemandReadAllocator(RecvByteBufAllocator sessionReads) {
        this.sessionReads = Objects.requireNonNull(sessionReads, "sessionReads");
    }

    /** Sizes {@code channel}'s reads from now on, keeping its own allocator for when it becomes a session. */
    public static void install(Channel channel) {
        ChannelConfig config = channel.config();
        config.setRecvByteBufAllocator(new DemandReadAllocator(config.getRecvByteBufAllocator()));
    }

    /** The connection has become a control session: from its next read on, reads are sized as Netty would. */
    public static void toSessionReads(Channel channel) {
        if (channel.config().getRecvByteBufAllocator() instanceof DemandReadAllocator allocator) {
            allocator.requestPhase = false;
            allocator.demand = 0;
        }
    }

    /** The allocator {@link #install} gave {@code channel}. */
    static DemandReadAllocator of(Channel channel) {
        if (channel.config().getRecvByteBufAllocator() instanceof DemandReadAllocator allocator) {
            return allocator;
        }
        throw new IllegalStateException("The edge connection's reads are not sized for exchanges");
    }

    /** The next read takes at most {@code bytes}, and the one after it {@link #HEAD_READ} again. */
    void demand(int bytes) {
        if (bytes < 1) {
            throw new IllegalArgumentException("A demand is at least 1 byte, not " + bytes);
        }
        demand = bytes;
    }

    /** Forgets a demand no read has taken yet. */
    void clear() {
        demand = 0;
    }

    /** An {@link ExtendedHandle}, since Netty's epoll channels require one and wrap it in their own. */
    @Override
    public ExtendedHandle newHandle() {
        return new Handle((ExtendedHandle) sessionReads.newHandle());
    }

    private int nextSize() {
        return demand > 0 ? demand : HEAD_READ;
    }

    /** One handle for the channel's life, so it answers for whichever phase the connection is in. */
    private final class Handle implements ExtendedHandle {

        private final ExtendedHandle session;
        private int attempted;
        private int lastRead;

        Handle(ExtendedHandle session) {
            this.session = session;
        }

        @Override
        public ByteBuf allocate(ByteBufAllocator alloc) {
            if (!requestPhase) {
                return session.allocate(alloc);
            }
            int size = nextSize();
            demand = 0;
            return alloc.ioBuffer(size);
        }

        @Override
        public int guess() {
            return requestPhase ? nextSize() : session.guess();
        }

        @Override
        public void reset(ChannelConfig config) {
            session.reset(config);
        }

        @Override
        public void incMessagesRead(int numMessages) {
            session.incMessagesRead(numMessages);
        }

        @Override
        public void lastBytesRead(int bytes) {
            lastRead = bytes;
            if (!requestPhase) {
                session.lastBytesRead(bytes);
            }
        }

        @Override
        public int lastBytesRead() {
            return requestPhase ? lastRead : session.lastBytesRead();
        }

        @Override
        public void attemptedBytesRead(int bytes) {
            attempted = bytes;
            if (!requestPhase) {
                session.attemptedBytesRead(bytes);
            }
        }

        @Override
        public int attemptedBytesRead() {
            return requestPhase ? attempted : session.attemptedBytesRead();
        }

        @Override
        public boolean continueReading() {
            return !requestPhase && session.continueReading();
        }

        @Override
        public boolean continueReading(UncheckedBooleanSupplier maybeMoreDataSupplier) {
            return !requestPhase && session.continueReading(maybeMoreDataSupplier);
        }

        @Override
        public void readComplete() {
            if (!requestPhase) {
                session.readComplete();
            }
        }
    }
}
