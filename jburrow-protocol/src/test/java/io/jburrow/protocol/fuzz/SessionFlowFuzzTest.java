package io.jburrow.protocol.fuzz;

import static io.jburrow.protocol.stream.StreamState.CLOSED;
import static io.jburrow.protocol.stream.StreamState.HALF_CLOSED_LOCAL;
import static io.jburrow.protocol.stream.StreamState.HALF_CLOSED_REMOTE;
import static io.jburrow.protocol.stream.StreamState.OPEN;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.argumentSet;

import com.code_intelligence.jazzer.junit.FuzzTest;
import io.jburrow.protocol.flow.Credit;
import io.jburrow.protocol.flow.FlowLimits;
import io.jburrow.protocol.flow.ReceiveWindow;
import io.jburrow.protocol.flow.SessionFlow;
import io.jburrow.protocol.flow.StreamFlow;
import io.jburrow.protocol.stream.StreamEvent;
import io.jburrow.protocol.stream.StreamLifecycle;
import io.jburrow.protocol.stream.StreamOutcome;
import io.jburrow.protocol.stream.StreamOutcome.Accept;
import io.jburrow.protocol.stream.StreamOutcome.Discard;
import io.jburrow.protocol.stream.StreamOutcome.ResetStream;
import io.jburrow.protocol.stream.StreamState;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Two peers built on {@link SessionFlow}, with a FIFO of frames in flight each way, driven by a script of sends,
 * reservations, refunds, deliveries, releases, parking, half-closes and resets in any order. A is the server and opens
 * every stream; B learns of one when its {@code OPEN} arrives. Each peer keeps its streams' {@link StreamLifecycle} next
 * to their {@link StreamFlow}, as each side's domain does, so the lifecycle's {@code WINDOW_UPDATE} columns are
 * exercised with the model. A stream that can no longer send refunds what it has reserved, and one that leaves its
 * table with bytes parked is released through its flow rather than as session credit only.
 *
 * <p>After every step:
 *
 * <ol>
 *   <li><b>No overrun.</b> Nothing throws: a sender that follows the model, reserving or not, never causes a
 *       {@code FLOW_CONTROL_ERROR}, and the model never asks for a {@code WINDOW_UPDATE} the stream's state forbids.
 *   <li><b>Bounded holding.</b> No receiver holds more unparked bytes than its session window, nor more bytes, parked or
 *       not, than its stream window on a stream.
 *   <li><b>Conservation.</b> Per direction, for the session and for every stream its sender may still send on: sender
 *       credit + reserved + {@code DATA} in flight + receiver held + receiver unreturned + updates in flight = the
 *       window. Parked bytes count as released in the session's column and as held in the stream's.
 *   <li><b>Bounded parking.</b> The bytes a receiver has parked are the sum of its streams' parked bytes, and never more
 *       than the stream windows of the streams that parked them.
 * </ol>
 *
 * <p>At the end both peers refund every reservation, release everything and deliver everything until nothing moves.
 * Then every credit a sender may still use is more than half its window. That is the no-deadlock property, and it also
 * proves credit goes back at half the window: a receiver that waited longer leaves its sender with less.
 *
 * <p>The script is read from the front, a byte at a time, so the seed corpus is written by {@link ScriptBuilder} and
 * kept in step with it by {@link #seedsAreCommitted}.
 */
class SessionFlowFuzzTest {

    private static final int MIN_STREAM_WINDOW = FlowLimits.MIN_STREAM_WINDOW;
    private static final int MAX_STREAM_WINDOW = 1 << 20;
    private static final int MAX_SESSION_WINDOW = 4 << 20;
    private static final int MAX_STREAMS = 64;

    private static final int OPEN_STREAM = 0;
    private static final int SEND = 1;
    private static final int DELIVER = 2;
    private static final int RELEASE = 3;
    private static final int CLOSE = 4;
    private static final int RESET = 5;
    private static final int DELIVER_ALL = 6;
    private static final int RELEASE_ALL = 7;
    private static final int RESERVE = 0;
    private static final int SEND_RESERVED = 1;
    private static final int REFUND = 2;
    private static final int REFUND_ALL = 3;
    private static final int PARK = 4;
    private static final int EXTENDED_ACTIONS = 5;
    /** Set in an op for the reservation and parking actions; the phase 2 seeds never set it, so they keep their meaning. */
    private static final int EXTENDED = 0x10;

    private static final int A = 0;
    private static final int B = 1;

    @FuzzTest(maxDuration = "2m")
    void peers(byte[] script) {
        new Simulation(new Script(script)).run();
    }

    private static final String UPDATE_PROPERTY = "update.seeds";
    private static final Path INPUTS = Path.of(System.getProperty("basedir", "."))
            .resolve("src/test/resources/io/jburrow/protocol/fuzz/SessionFlowFuzzTestInputs/peers");

    /** Hand-written scripts that reach the states the model exists for, as starting points for the fuzzer. */
    static Map<String, byte[]> seeds() {
        Map<String, byte[]> seeds = new LinkedHashMap<>();
        seeds.put(
                "one-stream-to-exhaustion-and-back",
                new ScriptBuilder(65_536, 65_536, 65_536, 65_536)
                        .open()
                        .deliverAll(A)
                        .send(A, 0, 65_536)
                        .send(A, 0, 1)
                        .deliverAll(A)
                        .release(B, 0, 32_767)
                        .send(A, 0, 1)
                        .release(B, 0, 1)
                        .deliverAll(B)
                        .send(A, 0, 32_768)
                        .send(B, 0, 65_536)
                        .deliverAll(B)
                        .releaseAll(A)
                        .deliverAll(A)
                        .send(B, 0, 65_536)
                        .build());
        ScriptBuilder manyStreams = new ScriptBuilder(65_536, 65_536, 65_536, 65_536);
        for (int i = 0; i < 8; i++) {
            manyStreams.open();
        }
        manyStreams.deliverAll(A);
        for (int i = 0; i < 8; i++) {
            manyStreams.send(A, i, 20_000);
        }
        seeds.put(
                "many-streams-small-session",
                manyStreams
                        .deliverAll(A)
                        .release(B, 0, 20_000)
                        .release(B, 1, 15_000)
                        .deliverAll(B)
                        .send(A, 4, 20_000)
                        .send(A, 5, 20_000)
                        .deliverAll(A)
                        .releaseAll(B)
                        .deliverAll(B)
                        .send(A, 6, 65_536)
                        .send(A, 7, 65_536)
                        .build());
        seeds.put(
                "resets-with-data-in-flight",
                new ScriptBuilder(65_536, 131_072, 65_536, 98_304)
                        .open()
                        .open()
                        .deliverAll(A)
                        .send(A, 0, 65_536)
                        .send(A, 1, 30_000)
                        .reset(B, 0)
                        .deliverAll(A)
                        .deliverAll(B)
                        .release(B, 0, 10_000)
                        .send(B, 1, 40_000)
                        .deliverAll(B)
                        .reset(A, 1)
                        .deliverAll(A)
                        .release(B, 0, 5_000)
                        .releaseAll(A)
                        .releaseAll(B)
                        .deliverAll(B)
                        .build());
        seeds.put(
                "half-close-then-updates",
                new ScriptBuilder(65_536, 65_536, 65_536, 65_536)
                        .open()
                        .deliverAll(A)
                        .send(A, 0, 60_000)
                        .deliverAll(A)
                        .release(B, 0, 60_000)
                        .close(A, 0)
                        .deliverAll(B)
                        .deliverAll(A)
                        .send(B, 0, 50_000)
                        .deliverAll(B)
                        .releaseAll(A)
                        .deliverAll(A)
                        .close(B, 0)
                        .deliver(B)
                        .open()
                        .deliverAll(A)
                        .send(A, 1, 40_000)
                        .close(A, 1)
                        .deliverAll(A)
                        .release(B, 0, 40_000)
                        .deliverAll(B)
                        .build());
        seeds.put(
                "both-directions-odd-windows",
                new ScriptBuilder(70_001, 150_001, 65_537, 65_537)
                        .open()
                        .open()
                        .deliver(A)
                        .send(A, 0, 65_537)
                        .deliver(A)
                        .send(B, 1, 65_536)
                        .send(B, 0, 4_465)
                        .deliver(A)
                        .deliver(B)
                        .release(B, 0, 32_769)
                        .release(A, 0, 35_000)
                        .deliver(B)
                        .deliver(A)
                        .deliverAll(B)
                        .send(A, 1, 65_536)
                        .send(B, 0, 70_001)
                        .build());
        seeds.put(
                "reservations-sent-and-refunded",
                new ScriptBuilder(65_536, 98_304, 65_536, 65_536)
                        .open()
                        .open()
                        .deliverAll(A)
                        .reserve(A, 0, 16_384)
                        .sendReserved(A, 0, 10_000)
                        .refund(A, 0, 6_384)
                        .reserve(A, 1, 65_535)
                        .send(A, 0, 1)
                        .deliverAll(A)
                        .releaseAll(B)
                        .deliverAll(B)
                        .sendReserved(A, 1, 50_000)
                        .reserve(A, 0, 65_535)
                        .reset(A, 1)
                        .sendReserved(A, 0, 65_535)
                        .deliverAll(A)
                        .refundAll(A, 0)
                        .reserve(B, 0, 0)
                        .reserve(B, 0, 40_000)
                        .sendReserved(B, 0, 40_000)
                        .deliverAll(B)
                        .build());
        seeds.put(
                "parked-streams-beside-a-busy-one",
                new ScriptBuilder(65_536, 65_536, 65_536, 65_536)
                        .open()
                        .open()
                        .open()
                        .deliverAll(A)
                        .send(B, 0, 32_768)
                        .send(B, 1, 32_768)
                        .deliverAll(B)
                        .send(B, 2, 1)
                        .park(A, 0)
                        .park(A, 0)
                        .deliverAll(A)
                        .send(B, 2, 32_768)
                        .park(A, 1)
                        .deliverAll(A)
                        .send(B, 0, 65_535)
                        .deliverAll(B)
                        .release(A, 0, 40_000)
                        .park(A, 0)
                        .deliverAll(A)
                        .send(B, 2, 65_535)
                        .deliverAll(B)
                        .releaseAll(A)
                        .deliverAll(A)
                        .build());
        seeds.put(
                "parked-streams-reset-and-closed",
                new ScriptBuilder(65_536, 131_072, 65_536, 65_536)
                        .open()
                        .open()
                        .deliverAll(A)
                        .send(B, 0, 65_536)
                        .send(B, 1, 40_000)
                        .deliverAll(B)
                        .park(A, 0)
                        .park(A, 1)
                        .send(B, 1, 20_000)
                        .close(B, 1)
                        .deliverAll(B)
                        .reset(A, 0)
                        .close(A, 1)
                        .deliverAll(A)
                        .release(A, 0, 30_000)
                        .release(A, 1, 50_000)
                        .park(A, 0)
                        .releaseAll(A)
                        .deliverAll(A)
                        .build());
        return seeds;
    }

    static Stream<Arguments> committedSeeds() {
        return seeds().entrySet().stream().map(seed -> argumentSet(seed.getKey(), seed.getKey(), seed.getValue()));
    }

    /**
     * Run with {@code -Dupdate.seeds=true} to write the seeds into the source tree, then commit them. A file named
     * {@code finding-*} is a past fuzzing finding and is left alone.
     */
    @ParameterizedTest
    @MethodSource("committedSeeds")
    void seedsAreCommitted(String name, byte[] script) throws IOException {
        Path file = INPUTS.resolve(name);
        if (Boolean.getBoolean(UPDATE_PROPERTY)) {
            Files.createDirectories(INPUTS);
            Files.write(file, script);
        }

        assertThat(file)
                .as("seed %s; regenerate the seeds with -D%s=true", name, UPDATE_PROPERTY)
                .exists()
                .hasBinaryContent(script);
    }

    private sealed interface Frame {}

    private record OpenFrame(int stream) implements Frame {}

    private record DataFrame(int stream, int bytes) implements Frame {}

    private record CloseFrame(int stream) implements Frame {}

    private record ResetFrame(int stream) implements Frame {}

    private record StreamUpdate(int stream, int increment) implements Frame {}

    private record SessionUpdate(int increment) implements Frame {}

    /** One stream as a side's domain holds it: its lifecycle and its flow, side by side. */
    private static final class Entry {
        final int id;
        final StreamLifecycle lifecycle = new StreamLifecycle();
        final StreamFlow flow;

        Entry(int id, StreamFlow flow) {
            this.id = id;
            this.flow = flow;
        }

        StreamState state() {
            return lifecycle.state();
        }

        boolean maySend() {
            return state() == OPEN || state() == HALF_CLOSED_REMOTE;
        }

        boolean isLive() {
            return state() != CLOSED;
        }
    }

    private static final class Peer {

        final String name;
        final int streamWindow;
        final int sessionWindow;
        final SessionFlow flow;
        final Map<Integer, Entry> streams = new HashMap<>();
        final List<Entry> order = new ArrayList<>();
        final ArrayDeque<Frame> outbound = new ArrayDeque<>();

        /**
         * Bytes still held for streams that left their table with nothing parked: they release session credit only, as
         * in phase 2.
         */
        int orphaned;

        /** Streams that left their table with bytes parked: released through their flow, which knows which are parked. */
        final List<Entry> parkedOrphans = new ArrayList<>();

        Peer(String name, int streamWindow, int sessionWindow, int peerStreamWindow, int peerSessionWindow) {
            this.name = name;
            this.streamWindow = streamWindow;
            this.sessionWindow = sessionWindow;
            this.flow = new SessionFlow(streamWindow, sessionWindow, peerStreamWindow, peerSessionWindow);
        }

        Entry add(int id) {
            assertThat(streams).as("%s already knows stream %d", name, id).doesNotContainKey(id);
            Entry entry = new Entry(id, flow.openStream());
            streams.put(id, entry);
            order.add(entry);
            return entry;
        }

        Entry pick(int selector) {
            return order.isEmpty() ? null : order.get(selector % order.size());
        }

        void send(int selector, int size) {
            Entry entry = pick(selector);
            if (entry == null || !entry.maySend()) {
                return;
            }
            int sendable = flow.sendable(entry.flow);
            if (sendable == 0) {
                return;
            }
            int bytes = Math.min(sendable, size);
            flow.onSent(entry.flow, bytes);
            entry.lifecycle.on(StreamEvent.DATA_SENT);
            outbound.add(new DataFrame(entry.id, bytes));
        }

        void reserve(int selector, int max) {
            Entry entry = pick(selector);
            if (entry != null && entry.maySend()) {
                flow.reserve(entry.flow, max);
            }
        }

        void sendReserved(int selector, int size) {
            Entry entry = pick(selector);
            if (entry == null || !entry.maySend() || entry.flow.reserved() == 0) {
                return;
            }
            int bytes = Math.min(entry.flow.reserved(), size);
            flow.onSentReserved(entry.flow, bytes);
            entry.lifecycle.on(StreamEvent.DATA_SENT);
            outbound.add(new DataFrame(entry.id, bytes));
        }

        void refund(int selector, int amount) {
            Entry entry = pick(selector);
            if (entry != null) {
                flow.refund(entry.flow, Math.min(entry.flow.reserved(), amount));
            }
        }

        void refundAll(int selector) {
            Entry entry = pick(selector);
            if (entry != null) {
                flow.refundAll(entry.flow);
            }
        }

        void park(int selector) {
            Entry entry = pick(selector);
            if (entry != null && entry.isLive()) {
                sendCredit(entry, flow.park(entry.flow));
            }
        }

        /** A stream that can no longer send gives back what it reserved, as an adapter must when a stream ends. */
        void stopSending(Entry entry) {
            if (!entry.maySend()) {
                flow.refundAll(entry.flow);
            }
        }

        void close(int selector) {
            Entry entry = pick(selector);
            if (entry == null || !entry.maySend()) {
                return;
            }
            entry.lifecycle.on(StreamEvent.CLOSE_SENT);
            stopSending(entry);
            outbound.add(new CloseFrame(entry.id));
            if (!entry.isLive()) {
                orphan(entry);
            }
        }

        void reset(int selector) {
            Entry entry = pick(selector);
            if (entry == null || !entry.isLive()) {
                return;
            }
            entry.lifecycle.on(StreamEvent.RST_SENT);
            outbound.add(new ResetFrame(entry.id));
            orphan(entry);
        }

        /** The stream's entry is gone, but its writes may still be pending: what it held is released later. */
        void orphan(Entry entry) {
            flow.onRemoved(entry.flow);
            stopSending(entry);
            if (entry.flow.parked() > 0) {
                parkedOrphans.add(entry);
            } else {
                orphaned += entry.flow.receive().held();
            }
        }

        List<Entry> holding() {
            List<Entry> holding = new ArrayList<>();
            for (Entry entry : order) {
                if (entry.isLive() && entry.flow.receive().held() > 0) {
                    holding.add(entry);
                }
            }
            for (Entry entry : parkedOrphans) {
                if (entry.flow.receive().held() > 0) {
                    holding.add(entry);
                }
            }
            return holding;
        }

        boolean holdsAnything() {
            return orphaned > 0 || !holding().isEmpty();
        }

        void release(int selector, int amount) {
            List<Entry> holding = holding();
            int choices = holding.size() + (orphaned > 0 ? 1 : 0);
            if (choices == 0) {
                return;
            }
            int choice = selector % choices;
            if (choice < holding.size()) {
                Entry entry = holding.get(choice);
                int bytes = 1 + amount % entry.flow.receive().held();
                releaseFrom(entry, bytes);
            } else {
                int bytes = 1 + amount % orphaned;
                orphaned -= bytes;
                sendCredit(null, flow.onReleased(null, bytes));
            }
        }

        void releaseAll() {
            for (Entry entry : holding()) {
                releaseFrom(entry, entry.flow.receive().held());
            }
            if (orphaned > 0) {
                int bytes = orphaned;
                orphaned = 0;
                sendCredit(null, flow.onReleased(null, bytes));
            }
        }

        private void releaseFrom(Entry entry, int bytes) {
            Credit credit = flow.onReleased(entry.flow, bytes);
            sendCredit(entry.isLive() ? entry : null, credit);
        }

        void refundEverything() {
            for (Entry entry : order) {
                flow.refundAll(entry.flow);
            }
        }

        private void sendCredit(Entry entry, Credit credit) {
            if (credit.session() > 0) {
                outbound.add(new SessionUpdate(credit.session()));
            }
            if (credit.stream() > 0) {
                assertThat(entry)
                        .as("%s: stream credit for a stream that is gone", name)
                        .isNotNull();
                entry.lifecycle.on(StreamEvent.WINDOW_UPDATE_SENT);
                outbound.add(new StreamUpdate(entry.id, credit.stream()));
            }
        }

        void receive(Frame frame) {
            switch (frame) {
                case OpenFrame open -> add(open.stream());
                case DataFrame data -> {
                    Entry entry = known(data.stream());
                    switch (entry.lifecycle.on(StreamEvent.DATA_RECEIVED)) {
                        case Accept ignored -> flow.onReceived(entry.flow, data.bytes());
                        case Discard ignored -> sendCredit(null, flow.onDiscarded(data.bytes()));
                        case ResetStream ignored -> {
                            sendCredit(null, flow.onDiscarded(data.bytes()));
                            resetByLifecycle(entry);
                        }
                    }
                }
                case CloseFrame close -> {
                    Entry entry = known(close.stream());
                    switch (entry.lifecycle.on(StreamEvent.CLOSE_RECEIVED)) {
                        case Accept accept -> {
                            flow.onPeerFinished(entry.flow);
                            if (accept.next() == CLOSED) {
                                orphan(entry);
                            }
                        }
                        case Discard ignored -> {}
                        case ResetStream ignored -> resetByLifecycle(entry);
                    }
                }
                case ResetFrame reset -> {
                    Entry entry = known(reset.stream());
                    if (entry.lifecycle.on(StreamEvent.RST_RECEIVED) instanceof Accept) {
                        orphan(entry);
                    }
                }
                case StreamUpdate update -> {
                    Entry entry = known(update.stream());
                    StreamOutcome outcome = entry.lifecycle.on(StreamEvent.WINDOW_UPDATE_RECEIVED);
                    if (outcome instanceof Accept accept && accept.next() != HALF_CLOSED_LOCAL) {
                        flow.onStreamUpdate(entry.flow, update.increment());
                    }
                }
                case SessionUpdate update -> flow.onSessionUpdate(update.increment());
            }
        }

        private void resetByLifecycle(Entry entry) {
            outbound.add(new ResetFrame(entry.id));
            orphan(entry);
        }

        private Entry known(int id) {
            Entry entry = streams.get(id);
            assertThat(entry)
                    .as("%s: a frame for stream %d arrived before its OPEN", name, id)
                    .isNotNull();
            return entry;
        }
    }

    private static final class Simulation {

        private final Script script;
        private final Peer a;
        private final Peer b;
        private int nextStreamId = 1;

        Simulation(Script script) {
            this.script = script;
            int aStream = streamWindow(script);
            int aSession = sessionWindow(script, aStream);
            int bStream = streamWindow(script);
            int bSession = sessionWindow(script, bStream);
            this.a = new Peer("A", aStream, aSession, bStream, bSession);
            this.b = new Peer("B", bStream, bSession, aStream, aSession);
        }

        private static int streamWindow(Script script) {
            return MIN_STREAM_WINDOW + script.u24() % (MAX_STREAM_WINDOW - MIN_STREAM_WINDOW + 1);
        }

        private static int sessionWindow(Script script, int streamWindow) {
            return streamWindow + script.u24() % (MAX_SESSION_WINDOW - streamWindow + 1);
        }

        void run() {
            while (script.hasMore()) {
                step(script.u8());
                checkInvariants();
            }
            drain();
            checkInvariants();
            checkCreditCameBack(a, b);
            checkCreditCameBack(b, a);
        }

        private void step(int op) {
            Peer side = ((op >> 3) & 1) == A ? a : b;
            if ((op & EXTENDED) != 0) {
                switch ((op & 0x07) % EXTENDED_ACTIONS) {
                    case RESERVE -> side.reserve(script.u8(), script.u16());
                    case SEND_RESERVED -> side.sendReserved(script.u8(), 1 + script.u16());
                    case REFUND -> side.refund(script.u8(), script.u16());
                    case REFUND_ALL -> side.refundAll(script.u8());
                    case PARK -> side.park(script.u8());
                    default -> throw new AssertionError(op);
                }
                return;
            }
            switch (op & 0x07) {
                case OPEN_STREAM -> {
                    if (a.order.size() < MAX_STREAMS) {
                        Entry entry = a.add(nextStreamId);
                        nextStreamId += 2;
                        a.outbound.add(new OpenFrame(entry.id));
                    }
                }
                case SEND -> side.send(script.u8(), 1 + script.u16());
                case DELIVER -> deliverOne(side);
                case RELEASE -> side.release(script.u8(), script.u24());
                case CLOSE -> side.close(script.u8());
                case RESET -> side.reset(script.u8());
                case DELIVER_ALL -> {
                    while (!side.outbound.isEmpty()) {
                        deliverOne(side);
                    }
                }
                case RELEASE_ALL -> side.releaseAll();
                default -> throw new AssertionError(op);
            }
        }

        private void deliverOne(Peer sender) {
            Frame frame = sender.outbound.poll();
            if (frame != null) {
                (sender == a ? b : a).receive(frame);
            }
        }

        /** Refunds every reservation, then delivers and releases until nothing is held and nothing is in flight. */
        private void drain() {
            a.refundEverything();
            b.refundEverything();
            while (!a.outbound.isEmpty() || !b.outbound.isEmpty() || a.holdsAnything() || b.holdsAnything()) {
                while (!a.outbound.isEmpty() || !b.outbound.isEmpty()) {
                    deliverOne(a);
                    deliverOne(b);
                }
                a.releaseAll();
                b.releaseAll();
            }
        }

        private void checkInvariants() {
            checkReserved(a);
            checkReserved(b);
            checkReceiver(a);
            checkReceiver(b);
            checkConservation(a, b);
            checkConservation(b, a);
        }

        /** The session's reservation is its streams', and only a stream that may still send holds one. */
        private static void checkReserved(Peer sender) {
            long reserved = 0;
            for (Entry entry : sender.order) {
                if (!entry.maySend()) {
                    assertThat(entry.flow.reserved())
                            .as("%s stream %d reserved after it stopped sending", sender.name, entry.id)
                            .isZero();
                }
                reserved += entry.flow.reserved();
            }
            assertThat((long) sender.flow.reserved())
                    .as("%s: session reserved vs its streams'", sender.name)
                    .isEqualTo(reserved);
        }

        private static void checkReceiver(Peer receiver) {
            ReceiveWindow session = receiver.flow.sessionReceive();
            checkWindow(receiver.name + " session", session, receiver.sessionWindow);
            assertThat(session.parked()).as("%s session parked", receiver.name).isZero();
            long unparkedHeld = receiver.orphaned;
            long parked = 0;
            long parkingStreams = 0;
            for (Entry entry : receiver.order) {
                ReceiveWindow window = entry.flow.receive();
                boolean tracked = entry.isLive() || receiver.parkedOrphans.contains(entry);
                if (!tracked) {
                    continue;
                }
                checkWindow(receiver.name + " stream " + entry.id, window, receiver.streamWindow);
                assertThat(window.parked())
                        .as("%s stream %d parked", receiver.name, entry.id)
                        .isBetween(0, window.held());
                unparkedHeld += window.held() - window.parked();
                parked += window.parked();
                parkingStreams += window.parked() > 0 ? 1 : 0;
            }
            assertThat(session.held())
                    .as("%s: session held vs its streams' unparked held plus orphaned", receiver.name)
                    .isEqualTo(unparkedHeld);
            assertThat(receiver.flow.parked())
                    .as("%s: parked vs its streams' parked", receiver.name)
                    .isEqualTo(parked)
                    .isLessThanOrEqualTo(parkingStreams * receiver.streamWindow);
        }

        private static void checkWindow(String what, ReceiveWindow window, int size) {
            assertThat(window.size()).as("%s size", what).isEqualTo(size);
            assertThat(window.held()).as("%s held", what).isBetween(0, size);
            assertThat((long) window.remaining() + window.held() + window.unreturned())
                    .as("%s: remaining + held + unreturned", what)
                    .isEqualTo(size);
        }

        private static void checkConservation(Peer sender, Peer receiver) {
            long sessionData = 0;
            Map<Integer, Long> streamData = new TreeMap<>();
            for (Frame frame : sender.outbound) {
                if (frame instanceof DataFrame data) {
                    sessionData += data.bytes();
                    streamData.merge(data.stream(), (long) data.bytes(), Long::sum);
                }
            }
            long sessionUpdates = 0;
            Map<Integer, Long> streamUpdates = new TreeMap<>();
            for (Frame frame : receiver.outbound) {
                if (frame instanceof SessionUpdate update) {
                    sessionUpdates += update.increment();
                } else if (frame instanceof StreamUpdate update) {
                    streamUpdates.merge(update.stream(), (long) update.increment(), Long::sum);
                }
            }

            ReceiveWindow session = receiver.flow.sessionReceive();
            assertThat(sender.flow.sessionSend().available()
                            + sender.flow.reserved()
                            + sessionData
                            + session.held()
                            + session.unreturned()
                            + sessionUpdates)
                    .as("%s -> %s session conservation", sender.name, receiver.name)
                    .isEqualTo(receiver.sessionWindow);

            for (Entry entry : sender.order) {
                Entry received = receiver.streams.get(entry.id);
                if (!entry.maySend() || (received != null && !received.isLive())) {
                    continue;
                }
                long held = received == null ? 0 : received.flow.receive().held();
                long unreturned = received == null ? 0 : received.flow.receive().unreturned();
                assertThat(entry.flow.send().available()
                                + entry.flow.reserved()
                                + streamData.getOrDefault(entry.id, 0L)
                                + held
                                + unreturned
                                + streamUpdates.getOrDefault(entry.id, 0L))
                        .as("%s -> %s stream %d conservation", sender.name, receiver.name, entry.id)
                        .isEqualTo(receiver.streamWindow);
            }
        }

        /** With nothing held and nothing in flight, the receiver owes less than half of each window it can still owe. */
        private static void checkCreditCameBack(Peer sender, Peer receiver) {
            long owed = receiver.sessionWindow - sender.flow.sessionSend().available();
            assertThat(owed)
                    .as("%s -> %s session credit owed after a full drain", sender.name, receiver.name)
                    .isLessThan(receiver.sessionWindow / 2);
            for (Entry entry : sender.order) {
                if (entry.maySend()) {
                    long streamOwed = receiver.streamWindow - entry.flow.send().available();
                    assertThat(streamOwed)
                            .as(
                                    "%s -> %s stream %d credit owed after a full drain",
                                    sender.name, receiver.name, entry.id)
                            .isLessThan(receiver.streamWindow / 2);
                    assertThat(sender.flow.sendable(entry.flow)).isPositive();
                }
            }
        }
    }

    /** Reads the fuzzer's input from the front. Past the end every read is 0, and the script stops. */
    private static final class Script {

        private final byte[] bytes;
        private int position;

        Script(byte[] bytes) {
            this.bytes = bytes;
        }

        boolean hasMore() {
            return position < bytes.length;
        }

        int u8() {
            return position < bytes.length ? bytes[position++] & 0xFF : 0;
        }

        int u16() {
            return u8() << 8 | u8();
        }

        int u24() {
            return u16() << 8 | u8();
        }
    }

    /** Writes the script {@link Script} reads, for the seed corpus. */
    private static final class ScriptBuilder {

        private final ByteArrayOutputStream out = new ByteArrayOutputStream();

        ScriptBuilder(int aStream, int aSession, int bStream, int bSession) {
            u24(aStream - MIN_STREAM_WINDOW);
            u24(aSession - aStream);
            u24(bStream - MIN_STREAM_WINDOW);
            u24(bSession - bStream);
        }

        ScriptBuilder open() {
            return op(OPEN_STREAM, A);
        }

        ScriptBuilder send(int side, int stream, int bytes) {
            op(SEND, side).u8(stream);
            return u16(bytes - 1);
        }

        ScriptBuilder deliver(int side) {
            return op(DELIVER, side);
        }

        ScriptBuilder deliverAll(int side) {
            return op(DELIVER_ALL, side);
        }

        /** {@code stream} indexes the side's streams that hold bytes, followed by the bytes of closed streams. */
        ScriptBuilder release(int side, int stream, int bytes) {
            op(RELEASE, side).u8(stream);
            return u24(bytes - 1);
        }

        ScriptBuilder releaseAll(int side) {
            return op(RELEASE_ALL, side);
        }

        ScriptBuilder close(int side, int stream) {
            return op(CLOSE, side).u8(stream);
        }

        ScriptBuilder reset(int side, int stream) {
            return op(RESET, side).u8(stream);
        }

        ScriptBuilder reserve(int side, int stream, int max) {
            return extended(RESERVE, side).u8(stream).u16(max);
        }

        ScriptBuilder sendReserved(int side, int stream, int bytes) {
            return extended(SEND_RESERVED, side).u8(stream).u16(bytes - 1);
        }

        ScriptBuilder refund(int side, int stream, int bytes) {
            return extended(REFUND, side).u8(stream).u16(bytes);
        }

        ScriptBuilder refundAll(int side, int stream) {
            return extended(REFUND_ALL, side).u8(stream);
        }

        ScriptBuilder park(int side, int stream) {
            return extended(PARK, side).u8(stream);
        }

        byte[] build() {
            return out.toByteArray();
        }

        private ScriptBuilder op(int action, int side) {
            return u8(side << 3 | action);
        }

        private ScriptBuilder extended(int action, int side) {
            return u8(EXTENDED | side << 3 | action);
        }

        private ScriptBuilder u8(int value) {
            out.write(value);
            return this;
        }

        private ScriptBuilder u16(int value) {
            return u8(value >> 8 & 0xFF).u8(value & 0xFF);
        }

        private ScriptBuilder u24(int value) {
            return u8(value >> 16 & 0xFF).u16(value & 0xFFFF);
        }
    }
}
