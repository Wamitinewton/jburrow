package io.jburrow.protocol.adapter.netty;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.channel.AdaptiveRecvByteBufAllocator;
import io.netty.channel.ChannelConfig;
import io.netty.channel.RecvByteBufAllocator;
import io.netty.util.UncheckedBooleanSupplier;
import java.util.Objects;
import java.util.function.IntSupplier;

/**
 * Sizes each socket read of a source (a visitor or a local connection) to at most what may be sent on right now, so a
 * read becomes one {@code DATA} frame with nothing left over. The caller supplies that number; this class knows nothing
 * about streams or sessions.
 *
 * <p>Reads are sized adaptively between 64 bytes and 64 KiB, as without credit, one read per event-loop turn so that
 * sources competing for a session's credit take turns. A read the cap cut short isn't recorded as a small one, so
 * credit pressure doesn't shrink later reads.
 *
 * <p>A read is never smaller than 1 byte, even with nothing sendable: on epoll a read into a buffer with no room comes
 * back as end of input, which would half-close the source. The caller holds that byte until credit returns.
 *
 * <p>One read per turn is a request Netty's epoll transport can override: once a source has sent {@code FIN}
 * ({@code EPOLLRDHUP}), it reads to the end of input in one turn, whatever {@link Handle#continueReading} answers and
 * whether {@code autoRead} is on or not. A read past the turn's first can only be that drain, and isn't capped: the
 * caller holds what it can't send, at most what the kernel had already received for the source, and reading it in
 * full-sized chunks rather than byte by byte keeps the drain from tying up the event loop.
 */
public final class CreditBoundedRecvByteBufAllocator implements RecvByteBufAllocator {

    static final int MIN_READ = 64;
    static final int INITIAL_READ = 2_048;
    static final int MAX_READ = 65_536;

    private final RecvByteBufAllocator adaptive =
            new AdaptiveRecvByteBufAllocator(MIN_READ, INITIAL_READ, MAX_READ).maxMessagesPerRead(1);
    private final IntSupplier sendable;

    /** @param sendable how many bytes may be sent right now; read on the source's event loop, once per decision */
    public CreditBoundedRecvByteBufAllocator(IntSupplier sendable) {
        this.sendable = Objects.requireNonNull(sendable, "sendable");
    }

    /** An {@link ExtendedHandle}, since Netty's epoll channels require one and wrap it in their own. */
    @Override
    public ExtendedHandle newHandle() {
        return new Handle((ExtendedHandle) adaptive.newHandle());
    }

    private final class Handle implements ExtendedHandle {

        private final ExtendedHandle adaptive;
        private int attempted;
        private int readsThisTurn;
        private boolean capped;
        private boolean cutShort;

        Handle(ExtendedHandle adaptive) {
            this.adaptive = adaptive;
        }

        @Override
        public int guess() {
            return bounded(adaptive.guess());
        }

        @Override
        public ByteBuf allocate(ByteBufAllocator alloc) {
            int unbounded = adaptive.guess();
            int size = bounded(unbounded);
            capped = size < unbounded;
            return alloc.ioBuffer(size);
        }

        private int bounded(int unbounded) {
            int cap = sendable.getAsInt();
            if (cap > 0) {
                return Math.min(unbounded, cap);
            }
            return readsThisTurn > 0 ? unbounded : 1;
        }

        @Override
        public void reset(ChannelConfig config) {
            adaptive.reset(config);
            readsThisTurn = 0;
            capped = false;
            cutShort = false;
        }

        @Override
        public void incMessagesRead(int numMessages) {
            adaptive.incMessagesRead(numMessages);
        }

        /**
         * The adaptive handle grows its guess when a read fills the buffer it asked for. A capped buffer isn't the one
         * it asked for, so it is told its own size instead and never sees a capped read as filling it.
         */
        @Override
        public void attemptedBytesRead(int bytes) {
            attempted = bytes;
            adaptive.attemptedBytesRead(capped ? adaptive.guess() : bytes);
        }

        @Override
        public int attemptedBytesRead() {
            return attempted;
        }

        @Override
        public void lastBytesRead(int bytes) {
            if (bytes > 0) {
                readsThisTurn++;
            }
            if (capped && bytes == attempted) {
                cutShort = true;
            }
            capped = false;
            adaptive.lastBytesRead(bytes);
        }

        @Override
        public int lastBytesRead() {
            return adaptive.lastBytesRead();
        }

        @Override
        public boolean continueReading() {
            return continueReading(() -> attempted == lastBytesRead());
        }

        @Override
        public boolean continueReading(UncheckedBooleanSupplier maybeMoreDataSupplier) {
            return adaptive.continueReading(maybeMoreDataSupplier) && sendable.getAsInt() > 0;
        }

        /** The adaptive handle records the turn's total as a sample, which a turn cut short by credit isn't. */
        @Override
        public void readComplete() {
            if (!cutShort) {
                adaptive.readComplete();
            }
            cutShort = false;
        }
    }
}
