import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Many-streams load driver for a TCP tunnel in front of an echo service.
 *
 * <p>Run with {@code java scripts/load/StreamLoad.java --host <host> --port <port> [options]}; {@code --help} lists
 * the options and {@code scripts/load/README.md} the scenarios. JDK 21 only, no dependencies.
 *
 * <p>Exit status: 0 when every transfer connection (and the probe, if any) got its bytes back intact, 1 when one
 * didn't or the run timed out, 2 for a usage error.
 */
public final class StreamLoad {

    private static final int CHUNK = 64 * 1024;
    private static final int PROBE_MESSAGE = 64;
    private static final long PROBE_INTERVAL_NANOS = TimeUnit.MILLISECONDS.toNanos(100);
    private static final long PROBE_SLOW_NANOS = TimeUnit.SECONDS.toNanos(1);
    /** Spreads per-connection seeds so neighbouring connections don't share a stream of random bytes. */
    private static final long SEED_STRIDE = 0x9E3779B97F4A7C15L;

    private static final PrintStream LOG = System.err;

    public static void main(String[] args) throws Exception {
        Options options;
        try {
            options = Options.parse(args);
        } catch (IllegalArgumentException e) {
            LOG.println("StreamLoad: " + e.getMessage());
            LOG.println("Run with --help for the options.");
            System.exit(2);
            return;
        }
        if (options == null) {
            System.out.print(Options.USAGE);
            return;
        }
        Report report = new StreamLoad(options).run();
        System.out.print(options.json() ? report.toJson() : report.toText());
        System.exit(report.ok() ? 0 : 1);
    }

    private final Options options;
    private final InetSocketAddress target;
    private final List<Socket> openSockets = new CopyOnWriteArrayList<>();
    private final AtomicInteger connected = new AtomicInteger();
    private final AtomicInteger peakConnected = new AtomicInteger();
    private volatile boolean stopping;

    private StreamLoad(Options options) {
        this.options = options;
        this.target = new InetSocketAddress(options.host(), options.port());
    }

    private Report run() throws InterruptedException {
        LOG.printf(
                "StreamLoad: %s:%d, %d streams x %s, %d stalled, probe %s, seed %d%n",
                options.host(),
                options.port(),
                options.streams(),
                Sizes.format(options.bytes()),
                options.stall(),
                options.probe() ? "on" : "off",
                options.seed());
        long start = System.nanoTime();
        long deadline = start + options.timeout().toNanos();

        Stalls stalls = new Stalls(options.stall());
        for (int i = 0; i < options.stall(); i++) {
            int index = i;
            Thread.ofVirtual().name("stall-" + i).start(() -> stall(index, deadline, stalls));
        }

        Probe probe = options.probe() ? new Probe() : null;
        Thread probeThread = null;
        if (probe != null) {
            probeThread = Thread.ofVirtual().name("probe").start(() -> probe(stalls, deadline, probe));
        }

        Transfer[] transfers = new Transfer[options.streams()];
        Thread[] transferThreads = new Thread[options.streams()];
        long rampNanos = options.ramp().toNanos();
        for (int i = 0; i < options.streams(); i++) {
            int index = i;
            long openAt = options.streams() > 1 ? start + rampNanos * i / (options.streams() - 1) : start;
            transferThreads[i] = Thread.ofVirtual()
                    .name("transfer-" + i)
                    .start(() -> transfers[index] = transfer(index, openAt, deadline));
        }

        boolean timedOut = false;
        for (Thread thread : transferThreads) {
            if (!thread.join(remaining(deadline))) {
                timedOut = true;
                break;
            }
        }
        long transfersDone = System.nanoTime();
        if (!timedOut) {
            sleepUntil(start + options.duration().toNanos());
        }

        stopping = true;
        openSockets.forEach(StreamLoad::closeQuietly);
        for (Thread thread : transferThreads) {
            thread.join(Duration.ofSeconds(5));
        }
        if (probeThread != null) {
            probeThread.join(Duration.ofSeconds(5));
        }
        stalls.awaitClosed(Duration.ofSeconds(5));

        return new Report(
                options,
                Arrays.asList(transfers),
                transfersDone - start,
                System.nanoTime() - start,
                peakConnected.get(),
                stalls,
                probe,
                timedOut);
    }

    /** One transfer connection: sends its bytes while reading the echo, then compares both digests. */
    private Transfer transfer(int index, long openAt, long deadline) {
        sleepUntil(openAt);
        long connectStart = System.nanoTime();
        Socket socket = new Socket();
        openSockets.add(socket);
        try {
            socket.connect(target, connectTimeoutMillis(deadline));
            long connectNanos = System.nanoTime() - connectStart;
            onConnected();
            try {
                return exchange(index, socket, connectStart, connectNanos);
            } finally {
                connected.decrementAndGet();
            }
        } catch (IOException e) {
            return Transfer.failed(index, stopping ? "TimedOut" : e.getClass().getSimpleName());
        } finally {
            closeQuietly(socket);
            openSockets.remove(socket);
        }
    }

    private Transfer exchange(int index, Socket socket, long connectStart, long connectNanos)
            throws IOException {
        MessageDigest sentDigest = sha256();
        AtomicLong sent = new AtomicLong();
        AtomicReference<IOException> sendFailure = new AtomicReference<>();
        OutputStream out = socket.getOutputStream();
        Thread sender = Thread.ofVirtual().name("send-" + index).start(() -> {
            SplittableRandom random = new SplittableRandom(connectionSeed(index));
            byte[] chunk = new byte[CHUNK];
            try {
                long left = options.bytes();
                while (left > 0) {
                    int n = (int) Math.min(chunk.length, left);
                    random.nextBytes(chunk);
                    sentDigest.update(chunk, 0, n);
                    out.write(chunk, 0, n);
                    sent.addAndGet(n);
                    left -= n;
                }
                socket.shutdownOutput();
            } catch (IOException e) {
                sendFailure.set(e);
            }
        });

        MessageDigest receivedDigest = sha256();
        long received = 0;
        IOException receiveFailure = null;
        try {
            InputStream in = socket.getInputStream();
            byte[] buffer = new byte[CHUNK];
            int n;
            while ((n = in.read(buffer)) >= 0) {
                receivedDigest.update(buffer, 0, n);
                received += n;
            }
        } catch (IOException e) {
            receiveFailure = e;
        }
        try {
            sender.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted waiting for the sender", e);
        }
        long completionNanos = System.nanoTime() - connectStart;

        IOException failure = sendFailure.get() != null ? sendFailure.get() : receiveFailure;
        if (failure != null) {
            return Transfer.failed(index, stopping ? "TimedOut" : failure.getClass().getSimpleName());
        }
        String sentHash = HexFormat.of().formatHex(sentDigest.digest());
        String receivedHash = HexFormat.of().formatHex(receivedDigest.digest());
        return new Transfer(
                index, null, connectNanos, completionNanos, sent.get(), received, sentHash, receivedHash);
    }

    /** A stalled connection: uploads for as long as the tunnel takes bytes and never reads the echo. */
    private void stall(int index, long deadline, Stalls stalls) {
        Socket socket = new Socket();
        openSockets.add(socket);
        boolean open = false;
        try {
            socket.connect(target, connectTimeoutMillis(deadline));
            onConnected();
            stalls.connected();
            open = true;
            SplittableRandom random = new SplittableRandom(connectionSeed(options.streams() + index));
            byte[] chunk = new byte[CHUNK];
            OutputStream out = socket.getOutputStream();
            while (!stopping) {
                random.nextBytes(chunk);
                out.write(chunk);
                stalls.uploaded(chunk.length);
            }
        } catch (IOException e) {
            if (!stopping) {
                stalls.failed(e.getClass().getSimpleName(), open);
            }
        } finally {
            stalls.closed();
            closeQuietly(socket);
            openSockets.remove(socket);
        }
    }

    /** Small echoes at a steady rate, timed one by one, once every stalled connection is open. */
    private void probe(Stalls stalls, long deadline, Probe probe) {
        try {
            stalls.awaitConnected(remaining(deadline));
        } catch (InterruptedException e) {
            return;
        }
        Socket socket = new Socket();
        openSockets.add(socket);
        try {
            socket.setTcpNoDelay(true);
            socket.connect(target, connectTimeoutMillis(deadline));
            OutputStream out = socket.getOutputStream();
            InputStream in = socket.getInputStream();
            SplittableRandom random = new SplittableRandom(connectionSeed(-1));
            byte[] message = new byte[PROBE_MESSAGE];
            byte[] echo = new byte[PROBE_MESSAGE];
            long next = System.nanoTime();
            while (!stopping) {
                random.nextBytes(message);
                long sentAt = System.nanoTime();
                out.write(message);
                out.flush();
                if (in.readNBytes(echo, 0, echo.length) < echo.length) {
                    probe.fail("EOFException");
                    return;
                }
                long rtt = System.nanoTime() - sentAt;
                if (!Arrays.equals(message, echo)) {
                    probe.fail("EchoMismatch");
                    return;
                }
                probe.record(rtt);
                next += PROBE_INTERVAL_NANOS;
                sleepUntil(next);
            }
        } catch (IOException e) {
            if (!stopping) {
                probe.fail(e.getClass().getSimpleName());
            }
        } finally {
            closeQuietly(socket);
            openSockets.remove(socket);
        }
    }

    private void onConnected() {
        int now = connected.incrementAndGet();
        peakConnected.accumulateAndGet(now, Math::max);
    }

    private long connectionSeed(int index) {
        return options.seed() + SEED_STRIDE * (index + 1L);
    }

    private static int connectTimeoutMillis(long deadline) {
        long millis = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
        return (int) Math.max(1, Math.min(Integer.MAX_VALUE, millis));
    }

    private static Duration remaining(long deadline) {
        return Duration.ofNanos(Math.max(1, deadline - System.nanoTime()));
    }

    private static void sleepUntil(long nanoTime) {
        long wait;
        while ((wait = nanoTime - System.nanoTime()) > 0) {
            try {
                TimeUnit.NANOSECONDS.sleep(wait);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private static void closeQuietly(Socket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
            // Closing is how the run ends a connection; a failure to close changes nothing.
        }
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Every JDK provides SHA-256", e);
        }
    }

    /** The outcome of one transfer connection; {@code failure} is null when it completed. */
    record Transfer(
            int index,
            String failure,
            long connectNanos,
            long completionNanos,
            long sent,
            long received,
            String sentHash,
            String receivedHash) {

        static Transfer failed(int index, String failure) {
            return new Transfer(index, failure, -1, -1, 0, 0, null, null);
        }

        boolean completed() {
            return failure == null;
        }

        boolean matches() {
            return completed() && sent == received && sentHash.equals(receivedHash);
        }
    }

    /** What the stalled connections did, shared by their threads. */
    static final class Stalls {
        private final CountDownLatch connected;
        private final CountDownLatch closed;
        private final AtomicInteger opened = new AtomicInteger();
        private final AtomicLong uploaded = new AtomicLong();
        private final Map<String, Integer> failures = new ConcurrentHashMap<>();

        Stalls(int count) {
            this.connected = new CountDownLatch(count);
            this.closed = new CountDownLatch(count);
        }

        void connected() {
            opened.incrementAndGet();
            connected.countDown();
        }

        void uploaded(int bytes) {
            uploaded.addAndGet(bytes);
        }

        void failed(String failure, boolean afterConnecting) {
            failures.merge(failure, 1, Integer::sum);
            if (!afterConnecting) {
                connected.countDown();
            }
        }

        void closed() {
            closed.countDown();
        }

        void awaitConnected(Duration timeout) throws InterruptedException {
            connected.await(timeout.toNanos(), TimeUnit.NANOSECONDS);
        }

        void awaitClosed(Duration timeout) throws InterruptedException {
            closed.await(timeout.toNanos(), TimeUnit.NANOSECONDS);
        }
    }

    /** The probe's round trips, recorded by its one thread and read after it stops. */
    static final class Probe {
        private final List<Long> rtts = new CopyOnWriteArrayList<>();
        private volatile String failure;

        void record(long rttNanos) {
            rtts.add(rttNanos);
        }

        void fail(String failure) {
            this.failure = failure;
        }
    }

    /** The run's results, rendered as text or JSON. */
    record Report(
            Options options,
            List<Transfer> transfers,
            long transferNanos,
            long wallNanos,
            int peakConnected,
            Stalls stalls,
            Probe probe,
            boolean timedOut) {

        private List<Transfer> present() {
            return transfers.stream()
                    .map(t -> t != null ? t : Transfer.failed(-1, "TimedOut"))
                    .toList();
        }

        int succeeded() {
            return (int) present().stream().filter(Transfer::matches).count();
        }

        List<Transfer> mismatches() {
            return present().stream()
                    .filter(t -> t.completed() && !t.matches())
                    .toList();
        }

        Map<String, Integer> failures() {
            Map<String, Integer> failures = new TreeMap<>();
            present().stream()
                    .filter(t -> !t.completed())
                    .forEach(t -> failures.merge(t.failure(), 1, Integer::sum));
            return failures;
        }

        boolean ok() {
            return !timedOut
                    && succeeded() == options.streams()
                    && (probe == null || (probe.failure == null && !probe.rtts.isEmpty()));
        }

        long totalBytes() {
            return present().stream().mapToLong(t -> t.sent() + t.received()).sum();
        }

        double throughputMiBps() {
            return transferNanos > 0 ? totalBytes() / 1048576.0 / (transferNanos / 1e9) : 0;
        }

        Percentiles connect() {
            return Percentiles.of(present().stream()
                    .filter(Transfer::completed)
                    .mapToLong(Transfer::connectNanos)
                    .toArray());
        }

        Percentiles completion() {
            return Percentiles.of(present().stream()
                    .filter(Transfer::completed)
                    .mapToLong(Transfer::completionNanos)
                    .toArray());
        }

        Percentiles probeRtt() {
            return Percentiles.of(probe.rtts.stream().mapToLong(Long::longValue).toArray());
        }

        long probeSlow() {
            return probe.rtts.stream().filter(rtt -> rtt > PROBE_SLOW_NANOS).count();
        }

        String toText() {
            StringBuilder out = new StringBuilder();
            line(out, "result", ok() ? "OK" : "FAILED" + (timedOut ? " (timed out)" : ""));
            line(out, "seed", Long.toString(options.seed()));
            line(
                    out,
                    "transfers",
                    "%d of %d succeeded, %d checksum mismatches, failed %s"
                            .formatted(succeeded(), options.streams(), mismatches().size(), failures()));
            for (Transfer t : mismatches()) {
                line(
                        out,
                        "  mismatch",
                        "connection %d: sent %d bytes (%s), received %d bytes (%s)"
                                .formatted(t.index(), t.sent(), t.sentHash(), t.received(), t.receivedHash()));
            }
            line(out, "peak open", Integer.toString(peakConnected));
            line(out, "connect", connect().toText());
            line(out, "completion", completion().toText());
            line(
                    out,
                    "throughput",
                    "%.2f MiB/s (%s both ways in %.2f s)"
                            .formatted(throughputMiBps(), Sizes.format(totalBytes()), transferNanos / 1e9));
            if (options.stall() > 0) {
                line(
                        out,
                        "stalled",
                        "%d of %d opened, %s uploaded before the cut, failed %s"
                                .formatted(
                                        stalls.opened.get(),
                                        options.stall(),
                                        Sizes.format(stalls.uploaded.get()),
                                        new TreeMap<>(stalls.failures)));
            }
            if (probe != null) {
                line(
                        out,
                        "probe rtt",
                        "%s, %d samples, %d over 1 s%s"
                                .formatted(
                                        probeRtt().toText(),
                                        probe.rtts.size(),
                                        probeSlow(),
                                        probe.failure != null ? ", failed " + probe.failure : ""));
            }
            line(out, "wall time", "%.2f s".formatted(wallNanos / 1e9));
            return out.toString();
        }

        private static void line(StringBuilder out, String label, String value) {
            out.append(String.format(Locale.ROOT, "%-12s %s%n", label, value));
        }

        String toJson() {
            Map<String, Object> json = new LinkedHashMap<>();
            json.put("ok", ok());
            json.put("timedOut", timedOut);
            json.put("seed", options.seed());
            json.put("host", options.host());
            json.put("port", options.port());
            json.put("streams", options.streams());
            json.put("bytes", options.bytes());
            json.put("rampMs", options.ramp().toMillis());
            json.put("stall", options.stall());
            json.put("succeeded", succeeded());
            json.put("mismatches", mismatches().stream().map(Transfer::index).toList());
            json.put("failed", failures());
            json.put("peakOpen", peakConnected);
            json.put("connectMs", connect().toJson());
            json.put("completionMs", completion().toJson());
            json.put("totalBytes", totalBytes());
            json.put("transferSeconds", round(transferNanos / 1e9));
            json.put("throughputMiBps", round(throughputMiBps()));
            if (options.stall() > 0) {
                Map<String, Object> stalled = new LinkedHashMap<>();
                stalled.put("opened", stalls.opened.get());
                stalled.put("uploadedBytes", stalls.uploaded.get());
                stalled.put("failed", new TreeMap<>(stalls.failures));
                json.put("stalled", stalled);
            }
            if (probe != null) {
                Map<String, Object> probed = new LinkedHashMap<>(probeRtt().toJson());
                probed.put("samples", probe.rtts.size());
                probed.put("over1s", probeSlow());
                probed.put("failed", probe.failure);
                json.put("probeRttMs", probed);
            }
            json.put("wallSeconds", round(wallNanos / 1e9));
            return Json.write(json) + System.lineSeparator();
        }
    }

    /** Nearest-rank percentiles of durations in nanoseconds. */
    record Percentiles(int count, long p50, long p99, long max) {

        static Percentiles of(long[] values) {
            if (values.length == 0) {
                return new Percentiles(0, 0, 0, 0);
            }
            long[] sorted = values.clone();
            Arrays.sort(sorted);
            return new Percentiles(sorted.length, rank(sorted, 50), rank(sorted, 99), sorted[sorted.length - 1]);
        }

        private static long rank(long[] sorted, int percentile) {
            int rank = (int) Math.ceil(percentile / 100.0 * sorted.length);
            return sorted[Math.max(0, rank - 1)];
        }

        String toText() {
            if (count == 0) {
                return "none";
            }
            return "p50 %s  p99 %s  max %s".formatted(millis(p50), millis(p99), millis(max));
        }

        Map<String, Object> toJson() {
            Map<String, Object> json = new LinkedHashMap<>();
            json.put("p50", round(p50 / 1e6));
            json.put("p99", round(p99 / 1e6));
            json.put("max", round(max / 1e6));
            return json;
        }

        private static String millis(long nanos) {
            return String.format(Locale.ROOT, "%.1f ms", nanos / 1e6);
        }
    }

    private static double round(double value) {
        return Math.round(value * 1000) / 1000.0;
    }

    /** Just enough JSON for the summary: maps, lists, strings, numbers, booleans and null. */
    static final class Json {
        static String write(Object value) {
            StringBuilder out = new StringBuilder();
            write(out, value);
            return out.toString();
        }

        private static void write(StringBuilder out, Object value) {
            switch (value) {
                case null -> out.append("null");
                case String s -> string(out, s);
                case Number n -> out.append(n);
                case Boolean b -> out.append(b);
                case Map<?, ?> map -> {
                    out.append('{');
                    boolean first = true;
                    for (Map.Entry<?, ?> entry : map.entrySet()) {
                        if (!first) {
                            out.append(',');
                        }
                        first = false;
                        string(out, entry.getKey().toString());
                        out.append(':');
                        write(out, entry.getValue());
                    }
                    out.append('}');
                }
                case List<?> list -> {
                    out.append('[');
                    for (int i = 0; i < list.size(); i++) {
                        if (i > 0) {
                            out.append(',');
                        }
                        write(out, list.get(i));
                    }
                    out.append(']');
                }
                default -> throw new IllegalArgumentException("Not JSON: " + value.getClass());
            }
        }

        private static void string(StringBuilder out, String s) {
            out.append('"');
            for (char c : s.toCharArray()) {
                switch (c) {
                    case '"' -> out.append("\\\"");
                    case '\\' -> out.append("\\\\");
                    case '\n' -> out.append("\\n");
                    default -> {
                        if (c < 0x20) {
                            out.append(String.format("\\u%04x", (int) c));
                        } else {
                            out.append(c);
                        }
                    }
                }
            }
            out.append('"');
        }
    }

    /** Byte sizes as people write them: {@code 64}, {@code 256KiB}, {@code 1MiB}, {@code 2GiB}. */
    static final class Sizes {
        private static final Pattern SIZE = Pattern.compile("(\\d+)\\s*(B|KiB|MiB|GiB)?");

        static long parse(String text) {
            Matcher m = SIZE.matcher(text.trim());
            if (!m.matches()) {
                throw new IllegalArgumentException("not a size (64, 256KiB, 1MiB, 2GiB): " + text);
            }
            long value = Long.parseLong(m.group(1));
            int shift =
                    switch (m.group(2) == null ? "B" : m.group(2)) {
                        case "KiB" -> 10;
                        case "MiB" -> 20;
                        case "GiB" -> 30;
                        default -> 0;
                    };
            return value << shift;
        }

        static String format(long bytes) {
            if (bytes >= 1L << 30) {
                return String.format(Locale.ROOT, "%.2f GiB", bytes / (double) (1L << 30));
            }
            if (bytes >= 1L << 20) {
                return String.format(Locale.ROOT, "%.2f MiB", bytes / (double) (1L << 20));
            }
            if (bytes >= 1L << 10) {
                return String.format(Locale.ROOT, "%.2f KiB", bytes / (double) (1L << 10));
            }
            return bytes + " B";
        }
    }

    /** Durations as people write them: {@code 0}, {@code 500ms}, {@code 30s}, {@code 5m}. */
    static final class Durations {
        private static final Pattern DURATION = Pattern.compile("(\\d+)\\s*(ms|s|m)?");

        static Duration parse(String text) {
            Matcher m = DURATION.matcher(text.trim());
            if (!m.matches()) {
                throw new IllegalArgumentException("not a duration (0, 500ms, 30s, 5m): " + text);
            }
            long value = Long.parseLong(m.group(1));
            return switch (m.group(2) == null ? "s" : m.group(2)) {
                case "ms" -> Duration.ofMillis(value);
                case "m" -> Duration.ofMinutes(value);
                default -> Duration.ofSeconds(value);
            };
        }
    }

    record Options(
            String host,
            int port,
            int streams,
            long bytes,
            Duration ramp,
            int stall,
            boolean probe,
            Duration duration,
            Duration timeout,
            long seed,
            boolean json) {

        static final String USAGE =
                """
                Usage: java scripts/load/StreamLoad.java --host <host> --port <port> [options]

                Opens concurrent connections to a TCP tunnel in front of an echo service, sends seeded
                random bytes on each while reading the echo, and checks every SHA-256.

                  --host <host>        the tunnel's public host (required)
                  --port <port>        the tunnel's public port (required)
                  --streams <n>        concurrent transfer connections (default 100)
                  --bytes <size>       bytes each transfer sends and expects back (default 1MiB)
                  --ramp <duration>    spread transfer opens over this long (default 0: all at once)
                  --stall <n>          extra connections that upload continuously and never read (default 0)
                  --probe              one connection echoing 64 bytes every 100 ms, each round trip timed
                  --duration <d>       keep stalled connections and the probe running until at least this
                                       long after the start, even once the transfers are done (default 0)
                  --timeout <d>        the whole run fails past this (default 300s)
                  --seed <n>           seed for the random bytes, to replay a mismatch (default: random)
                  --json               print the summary as one JSON object
                  --help               this text

                Sizes: 64, 256KiB, 1MiB, 2GiB. Durations: 0, 500ms, 30s, 5m.
                """;

        /** The parsed options, or null when {@code --help} was asked for. */
        static Options parse(String[] args) {
            String host = null;
            int port = -1;
            int streams = 100;
            long bytes = 1L << 20;
            Duration ramp = Duration.ZERO;
            int stall = 0;
            boolean probe = false;
            Duration duration = Duration.ZERO;
            Duration timeout = Duration.ofSeconds(300);
            long seed = ThreadLocalRandom.current().nextLong();
            boolean json = false;
            List<String> rest = new ArrayList<>(List.of(args));
            while (!rest.isEmpty()) {
                String option = rest.removeFirst();
                switch (option) {
                    case "--help", "-h" -> {
                        return null;
                    }
                    case "--host" -> host = value(option, rest);
                    case "--port" -> port = intValue(option, rest, 1, 65535);
                    case "--streams" -> streams = intValue(option, rest, 0, 1_000_000);
                    case "--bytes" -> bytes = Sizes.parse(value(option, rest));
                    case "--ramp" -> ramp = Durations.parse(value(option, rest));
                    case "--stall" -> stall = intValue(option, rest, 0, 1_000_000);
                    case "--probe" -> probe = true;
                    case "--duration" -> duration = Durations.parse(value(option, rest));
                    case "--timeout" -> timeout = Durations.parse(value(option, rest));
                    case "--seed" -> seed = longValue(option, rest);
                    case "--json" -> json = true;
                    default -> throw new IllegalArgumentException("unknown option " + option);
                }
            }
            if (host == null || port < 0) {
                throw new IllegalArgumentException("--host and --port are required");
            }
            if (streams == 0 && stall == 0 && !probe) {
                throw new IllegalArgumentException("nothing to do: --streams, --stall and --probe are all off");
            }
            if (streams == 0 && duration.isZero()) {
                throw new IllegalArgumentException("--streams 0 needs a --duration to hold the run open for");
            }
            if (duration.compareTo(timeout) >= 0) {
                throw new IllegalArgumentException("--duration must be shorter than --timeout");
            }
            return new Options(host, port, streams, bytes, ramp, stall, probe, duration, timeout, seed, json);
        }

        private static String value(String option, List<String> rest) {
            if (rest.isEmpty()) {
                throw new IllegalArgumentException(option + " needs a value");
            }
            return rest.removeFirst();
        }

        private static int intValue(String option, List<String> rest, int min, int max) {
            long value = longValue(option, rest);
            if (value < min || value > max) {
                throw new IllegalArgumentException(option + " must be " + min + " to " + max + ": " + value);
            }
            return (int) value;
        }

        private static long longValue(String option, List<String> rest) {
            String text = value(option, rest);
            try {
                return Long.parseLong(text);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(option + " is not a number: " + text);
            }
        }
    }
}
