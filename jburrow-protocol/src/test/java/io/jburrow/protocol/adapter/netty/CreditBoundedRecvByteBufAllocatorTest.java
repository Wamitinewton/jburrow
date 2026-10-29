package io.jburrow.protocol.adapter.netty;

import static org.assertj.core.api.Assertions.assertThat;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.UnpooledByteBufAllocator;
import io.netty.channel.ChannelConfig;
import io.netty.channel.RecvByteBufAllocator;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** The handle, driven through the calls a Netty read loop makes, one read per turn. */
class CreditBoundedRecvByteBufAllocatorTest {

    private static final ByteBufAllocator ALLOC = UnpooledByteBufAllocator.DEFAULT;
    private static final int TURNS = 20;

    private final EmbeddedChannel channel = new EmbeddedChannel();
    private final ChannelConfig config = channel.config();
    private final AtomicInteger sendable = new AtomicInteger(Integer.MAX_VALUE);
    private final RecvByteBufAllocator.ExtendedHandle handle =
            new CreditBoundedRecvByteBufAllocator(sendable::get).newHandle();

    @AfterEach
    void close() {
        channel.finishAndReleaseAll();
    }

    @Test
    void theHandleIsExtendedForEpoll() {
        RecvByteBufAllocator.Handle plain = new CreditBoundedRecvByteBufAllocator(() -> 1).newHandle();

        assertThat(plain).isInstanceOf(RecvByteBufAllocator.ExtendedHandle.class);
    }

    @Test
    void guessesAdaptivelyWithinSixtyFourBytesAndSixtyFourKiB() {
        handle.reset(config);
        assertThat(handle.guess()).isEqualTo(CreditBoundedRecvByteBufAllocator.INITIAL_READ);

        for (int i = 0; i < TURNS; i++) {
            turn(handle.guess());
        }
        assertThat(handle.guess()).isEqualTo(CreditBoundedRecvByteBufAllocator.MAX_READ);

        for (int i = 0; i < TURNS * 8; i++) {
            turn(1);
        }
        assertThat(handle.guess()).isEqualTo(CreditBoundedRecvByteBufAllocator.MIN_READ);
    }

    @Test
    void theGuessIsCappedAtWhatIsSendable() {
        handle.reset(config);

        sendable.set(100);
        assertThat(handle.guess()).isEqualTo(100);
        sendable.set(2_047);
        assertThat(handle.guess()).isEqualTo(2_047);
        sendable.set(2_049);
        assertThat(handle.guess()).isEqualTo(2_048);
    }

    @Test
    void theGuessIsNeverBelowOneByte() {
        handle.reset(config);

        sendable.set(0);
        assertThat(handle.guess()).isEqualTo(1);
        sendable.set(-5);
        assertThat(handle.guess()).isEqualTo(1);
    }

    @Test
    void allocatesExactlyTheGuess() {
        handle.reset(config);
        sendable.set(300);

        ByteBuf capped = handle.allocate(ALLOC);
        sendable.set(0);
        ByteBuf floor = handle.allocate(ALLOC);
        sendable.set(Integer.MAX_VALUE);
        ByteBuf unbounded = handle.allocate(ALLOC);

        assertThat(capped.writableBytes()).isEqualTo(300);
        assertThat(floor.writableBytes()).isEqualTo(1);
        assertThat(unbounded.writableBytes()).isEqualTo(2_048);
        capped.release();
        floor.release();
        unbounded.release();
    }

    @Test
    void reportsTheAttemptAsTheCallerMadeIt() {
        handle.reset(config);
        sendable.set(300);

        ByteBuf buffer = read(300);

        assertThat(handle.attemptedBytesRead()).isEqualTo(300);
        assertThat(handle.lastBytesRead()).isEqualTo(300);
        buffer.release();
    }

    @Test
    void keepsReadingOnlyWhileSomethingIsSendable() {
        handle.reset(config);
        ByteBuf first = read(handle.guess());

        assertThat(handle.continueReading()).isTrue();
        sendable.set(0);
        assertThat(handle.continueReading()).isFalse();
        assertThat(handle.continueReading(() -> true)).isFalse();
        first.release();
    }

    @Test
    void readsOncePerTurn() {
        handle.reset(config);
        ByteBuf first = read(handle.guess());
        handle.incMessagesRead(1);

        assertThat(handle.continueReading()).isFalse();
        assertThat(handle.continueReading(() -> true)).isFalse();
        first.release();
    }

    @Test
    void aReadForcedPastTheTurnsFirstWithNothingSendableIsFullSized() {
        handle.reset(config);
        sendable.set(0);
        ByteBuf first = read(1);

        ByteBuf drained = handle.allocate(ALLOC);

        assertThat(handle.guess()).isEqualTo(CreditBoundedRecvByteBufAllocator.INITIAL_READ);
        assertThat(drained.writableBytes()).isEqualTo(CreditBoundedRecvByteBufAllocator.INITIAL_READ);
        first.release();
        drained.release();
    }

    @Test
    void aReadForcedPastTheTurnsFirstIsStillCappedWhileSomethingIsSendable() {
        handle.reset(config);
        sendable.set(300);
        ByteBuf first = read(300);
        sendable.set(200);

        assertThat(handle.guess()).isEqualTo(200);
        first.release();
    }

    @Test
    void theNextTurnStartsAtTheOneByteFloorAgain() {
        handle.reset(config);
        sendable.set(0);
        ByteBuf first = read(1);
        ByteBuf drained = read(CreditBoundedRecvByteBufAllocator.INITIAL_READ);
        handle.readComplete();

        handle.reset(config);

        assertThat(handle.guess()).isEqualTo(1);
        first.release();
        drained.release();
    }

    @Test
    void readsTheCapCutShortDontShrinkLaterGuesses() {
        sendable.set(100);
        for (int i = 0; i < TURNS; i++) {
            turn(100);
        }

        sendable.set(Integer.MAX_VALUE);
        assertThat(handle.guess()).isEqualTo(CreditBoundedRecvByteBufAllocator.INITIAL_READ);
    }

    @Test
    void readsShortOfACapStillShrinkLaterGuesses() {
        sendable.set(1_000);
        for (int i = 0; i < TURNS; i++) {
            turn(100);
        }

        sendable.set(Integer.MAX_VALUE);
        assertThat(handle.guess()).isLessThan(CreditBoundedRecvByteBufAllocator.INITIAL_READ);
    }

    @Test
    void aReadThatFillsAnUncappedBufferGrowsLaterGuesses() {
        sendable.set(CreditBoundedRecvByteBufAllocator.INITIAL_READ);
        for (int i = 0; i < TURNS; i++) {
            turn(CreditBoundedRecvByteBufAllocator.INITIAL_READ);
        }

        sendable.set(Integer.MAX_VALUE);
        assertThat(handle.guess()).isGreaterThan(CreditBoundedRecvByteBufAllocator.INITIAL_READ);
    }

    /** One loop turn: one read of {@code bytes} into a buffer the handle sized. */
    private void turn(int bytes) {
        handle.reset(config);
        ByteBuf buffer = read(bytes);
        handle.incMessagesRead(1);
        handle.readComplete();
        buffer.release();
    }

    private ByteBuf read(int bytes) {
        ByteBuf buffer = handle.allocate(ALLOC);
        handle.attemptedBytesRead(buffer.writableBytes());
        buffer.writeZero(bytes);
        handle.lastBytesRead(bytes);
        return buffer;
    }
}
