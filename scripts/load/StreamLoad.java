import java.io.BufferedOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ProtocolException;
import java.net.Socket;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
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
import java.util.function.ToLongFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * Many-streams load driver for a tunnel: a TCP tunnel in front of an echo service, or with {@code --http} an HTTP
 * tunnel in front of {@code scripts/http-echo-server.py}.
 *
 * <p>Run with {@code java scripts/load/StreamLoad.java --host <host> --port <port> [options]} or {@code java
 * scripts/load/StreamLoad.java --http --url <base> [options]}; {@code --help} lists the options and {@code
 * scripts/load/README.md} the scenarios. JDK 21 only, no dependencies.
 *
 * <p>Exit status: 0 when every transfer or request (and the probe, if any) got its bytes back intact, 1 when one
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
        Summary report = options.http() != null ? new HttpLoad(options).run() : new StreamLoad(options).run();
        System.out.print(options.json() ? report.toJson() : report.toText());
        System.exit(report.ok() ? 0 : 1);
    }

    /** A finished run, as the exit status, text and JSON. */
    interface Summary {
        boolean ok();

        String toText();

        String toJson();
    }

    private final Options options;
    private final InetSocketAddress target;
    private final Connections connections = new Connections();

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

        boolean timedOut = !joinAll(transferThreads, deadline);
        long transfersDone = System.nanoTime();
        if (!timedOut) {
            sleepUntil(start + options.duration().toNanos());
        }

        connections.stop();
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
                connections.peak(),
                stalls,
                probe,
                timedOut);
    }

    /** One transfer connection: sends its bytes while reading the echo, then compares both digests. */
    private Transfer transfer(int index, long openAt, long deadline) {
        sleepUntil(openAt);
        long connectStart = System.nanoTime();
        Socket socket = new Socket();
        connections.track(socket);
        try {
            socket.connect(target, connectTimeoutMillis(deadline));
            long connectNanos = System.nanoTime() - connectStart;
            connections.opened();
            try {
                return exchange(index, socket, connectStart, connectNanos);
            } finally {
                connections.closed();
            }
        } catch (IOException e) {
            return Transfer.failed(index, connections.failure(e));
        } finally {
            connections.release(socket);
        }
    }

    private Transfer exchange(int index, Socket socket, long connectStart, long connectNanos)
            throws IOException {
        MessageDigest sentDigest = sha256();
        AtomicLong sent = new AtomicLong();
        AtomicReference<IOException> sendFailure = new AtomicReference<>();
        OutputStream out = socket.getOutputStream();
        Thread sender = Thread.ofVirtual().name("send-" + index).start(() -> {
            SplittableRandom random = new SplittableRandom(connectionSeed(options, index));
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
        joinSender(sender);
        long completionNanos = System.nanoTime() - connectStart;

        IOException failure = sendFailure.get() != null ? sendFailure.get() : receiveFailure;
        if (failure != null) {
            return Transfer.failed(index, connections.failure(failure));
        }
        String sentHash = HexFormat.of().formatHex(sentDigest.digest());
        String receivedHash = HexFormat.of().formatHex(receivedDigest.digest());
        return new Transfer(
                index, null, connectNanos, completionNanos, sent.get(), received, sentHash, receivedHash);
    }

    /** A stalled connection: uploads for as long as the tunnel takes bytes and never reads the echo. */
    private void stall(int index, long deadline, Stalls stalls) {
        Socket socket = new Socket();
        connections.track(socket);
        boolean open = false;
        try {
            socket.connect(target, connectTimeoutMillis(deadline));
            connections.opened();
            stalls.connected();
            open = true;
            SplittableRandom random = new SplittableRandom(connectionSeed(options, options.streams() + index));
            byte[] chunk = new byte[CHUNK];
            OutputStream out = socket.getOutputStream();
            while (!connections.stopping()) {
                random.nextBytes(chunk);
                out.write(chunk);
                stalls.uploaded(chunk.length);
            }
        } catch (IOException e) {
            if (!connections.stopping()) {
                stalls.failed(e.getClass().getSimpleName(), open);
            }
        } finally {
            stalls.closed();
            connections.release(socket);
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
        connections.track(socket);
        try {
            socket.setTcpNoDelay(true);
            socket.connect(target, connectTimeoutMillis(deadline));
            OutputStream out = socket.getOutputStream();
            InputStream in = socket.getInputStream();
            SplittableRandom random = new SplittableRandom(connectionSeed(options, -1));
            byte[] message = new byte[PROBE_MESSAGE];
            byte[] echo = new byte[PROBE_MESSAGE];
            long next = System.nanoTime();
            while (!connections.stopping()) {
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
            if (!connections.stopping()) {
                probe.fail(e.getClass().getSimpleName());
            }
        } finally {
            connections.release(socket);
        }
    }

    private static long connectionSeed(Options options, int index) {
        return options.seed() + SEED_STRIDE * (index + 1L);
    }

    /** Joins every thread before the deadline; false when one is still running at it. */
    private static boolean joinAll(Thread[] threads, long deadline) throws InterruptedException {
        for (Thread thread : threads) {
            if (!thread.join(remaining(deadline))) {
                return false;
            }
        }
        return true;
    }

    private static void joinSender(Thread sender) throws IOException {
        try {
            sender.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted waiting for the sender", e);
        }
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

    private static String hex(MessageDigest digest) {
        return HexFormat.of().formatHex(digest.digest());
    }

    /** Every socket a run has open, so that ending the run can cut them all, and how many were open at once. */
    static final class Connections {
        private final List<Socket> sockets = new CopyOnWriteArrayList<>();
        private final AtomicInteger open = new AtomicInteger();
        private final AtomicInteger peak = new AtomicInteger();
        private final CountDownLatch stopped = new CountDownLatch(1);

        void track(Socket socket) {
            sockets.add(socket);
        }

        void release(Socket socket) {
            closeQuietly(socket);
            sockets.remove(socket);
        }

        void opened() {
            peak.accumulateAndGet(open.incrementAndGet(), Math::max);
        }

        void closed() {
            open.decrementAndGet();
        }

        int peak() {
            return peak.get();
        }

        void stop() {
            stopped.countDown();
            sockets.forEach(StreamLoad::closeQuietly);
        }

        boolean stopping() {
            return stopped.getCount() == 0;
        }

        void awaitStop() throws InterruptedException {
            stopped.await();
        }

        /** How a failure is reported: the exception's class, or {@code TimedOut} when the run cut the connection. */
        String failure(IOException e) {
            return stopping() ? "TimedOut" : e.getClass().getSimpleName();
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

        boolean ok() {
            return failure == null && !rtts.isEmpty();
        }

        Percentiles rtt() {
            return Percentiles.of(rtts.stream().mapToLong(Long::longValue).toArray());
        }

        long slow() {
            return rtts.stream().filter(rtt -> rtt > PROBE_SLOW_NANOS).count();
        }

        String toText() {
            return "%s, %d samples, %d over 1 s%s"
                    .formatted(rtt().toText(), rtts.size(), slow(), failure != null ? ", failed " + failure : "");
        }

        Map<String, Object> toJson() {
            Map<String, Object> json = new LinkedHashMap<>(rtt().toJson());
            json.put("samples", rtts.size());
            json.put("over1s", slow());
            json.put("failed", failure);
            return json;
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
            boolean timedOut)
            implements Summary {

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

        @Override
        public boolean ok() {
            return !timedOut && succeeded() == options.streams() && (probe == null || probe.ok());
        }

        long totalBytes() {
            return present().stream().mapToLong(t -> t.sent() + t.received()).sum();
        }

        double throughputMiBps() {
            return mibps(totalBytes(), transferNanos);
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

        @Override
        public String toText() {
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
                line(out, "probe rtt", probe.toText());
            }
            line(out, "wall time", "%.2f s".formatted(wallNanos / 1e9));
            return out.toString();
        }

        @Override
        public String toJson() {
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
                json.put("probeRttMs", probe.toJson());
            }
            json.put("wallSeconds", round(wallNanos / 1e9));
            return Json.write(json) + System.lineSeparator();
        }
    }

    /**
     * The HTTP mode: requests to an HTTP tunnel in front of {@code scripts/http-echo-server.py}, each on a connection
     * of its own, as browsers make them towards the ingress, which then pools them upstream.
     */
    static final class HttpLoad {
        private static final Http.Request HELLO = Http.Request.hello();
        private static final Http.Request STALL = new Http.Request("GET", "/stall", 0, false);
        private static final Body HELLO_BODY = Body.of("hello\n".getBytes(StandardCharsets.US_ASCII));

        private final Options options;
        private final HttpMode mode;
        private final Connections connections = new Connections();

        HttpLoad(Options options) {
            this.options = options;
            this.mode = options.http();
        }

        HttpReport run() throws InterruptedException {
            Http.Request request = mode.request();
            LOG.printf(
                    "StreamLoad: %s %s://%s%s, %d requests, %d at a time, %d stalled, probe %s, seed %d%n",
                    request.method(),
                    mode.target().scheme(),
                    mode.target().authority(),
                    request.describe(),
                    mode.requests(),
                    options.streams(),
                    options.stall(),
                    options.probe() ? "on" : "off",
                    options.seed());
            Body expected = expectedBody(request);

            long start = System.nanoTime();
            long deadline = start + options.timeout().toNanos();

            Stalls stalls = new Stalls(options.stall());
            for (int i = 0; i < options.stall(); i++) {
                Thread.ofVirtual().name("stall-" + i).start(() -> stall(deadline, stalls));
            }

            Probe probe = options.probe() ? new Probe() : null;
            Thread probeThread = null;
            if (probe != null) {
                probeThread = Thread.ofVirtual().name("probe").start(() -> probe(stalls, deadline, probe));
            }

            Exchange[] exchanges = new Exchange[mode.requests()];
            AtomicInteger next = new AtomicInteger();
            Thread[] workers = new Thread[options.streams()];
            for (int w = 0; w < workers.length; w++) {
                workers[w] = Thread.ofVirtual().name("worker-" + w).start(() -> {
                    while (!connections.stopping()) {
                        int index = next.getAndIncrement();
                        if (index >= exchanges.length) {
                            return;
                        }
                        exchanges[index] = exchange(index, request, expected, deadline);
                    }
                });
            }

            boolean timedOut = !joinAll(workers, deadline);
            long requestsDone = System.nanoTime();
            if (!timedOut) {
                sleepUntil(start + options.duration().toNanos());
            }

            connections.stop();
            for (Thread worker : workers) {
                worker.join(Duration.ofSeconds(5));
            }
            if (probeThread != null) {
                probeThread.join(Duration.ofSeconds(5));
            }
            stalls.awaitClosed(Duration.ofSeconds(5));

            return new HttpReport(
                    options,
                    Arrays.asList(exchanges),
                    requestsDone - start,
                    System.nanoTime() - start,
                    connections.peak(),
                    stalls,
                    probe,
                    timedOut);
        }

        /** The body a request without one of its own must get back; null for an echo, which checks what it sent. */
        private static Body expectedBody(Http.Request request) {
            if (request.bodyBytes() > 0) {
                return null;
            }
            if (request.download() >= 0) {
                return new Body(request.download(), PythonRandom.sha256(request.download()));
            }
            return HELLO_BODY;
        }

        /** One request on a connection of its own: sends the head and any body while reading the response. */
        private Exchange exchange(int index, Http.Request request, Body expected, long deadline) {
            long start = System.nanoTime();
            Socket raw = new Socket();
            connections.track(raw);
            try {
                Socket socket = mode.target().open(raw, deadline);
                long connected = System.nanoTime();
                connections.opened();
                try {
                    return exchange(index, socket, request, expected, start, connected);
                } finally {
                    connections.closed();
                }
            } catch (IOException e) {
                return Exchange.failed(index, connections.failure(e));
            } finally {
                connections.release(raw);
            }
        }

        private Exchange exchange(
                int index, Socket socket, Http.Request request, Body expected, long start, long connected)
                throws IOException {
            OutputStream out = new BufferedOutputStream(socket.getOutputStream(), CHUNK + 16);
            out.write(request.head(mode.target().authority()));
            out.flush();

            MessageDigest sentDigest = sha256();
            AtomicLong sent = new AtomicLong();
            AtomicReference<IOException> sendFailure = new AtomicReference<>();
            Thread sender = request.bodyBytes() > 0
                    ? Thread.ofVirtual()
                            .name("send-" + index)
                            .start(() -> sendBody(index, request, out, sentDigest, sent, sendFailure))
                    : null;

            Http.Head head = null;
            long headAt = -1;
            MessageDigest receivedDigest = sha256();
            long received = 0;
            IOException receiveFailure = null;
            try {
                Http.Reader reader = new Http.Reader(socket.getInputStream(), CHUNK);
                head = reader.head();
                headAt = System.nanoTime();
                if (head.status() == 200) {
                    received = reader.body(head, receivedDigest);
                }
            } catch (IOException e) {
                receiveFailure = e;
            }
            long end = System.nanoTime();
            if (sender != null) {
                if (receiveFailure != null || head.status() != 200) {
                    // The response is over without the whole body read; the sender may be blocked writing it.
                    closeQuietly(socket);
                }
                joinSender(sender);
            }

            if (head != null && head.status() != 200) {
                return Exchange.failed(index, head.describe());
            }
            IOException failure = receiveFailure != null ? receiveFailure : sendFailure.get();
            if (failure != null) {
                return Exchange.failed(index, connections.failure(failure));
            }
            Body expect = expected != null ? expected : new Body(sent.get(), hex(sentDigest));
            return new Exchange(
                    index,
                    null,
                    connected - start,
                    headAt - start,
                    end - start,
                    sent.get(),
                    received,
                    expect,
                    hex(receivedDigest));
        }

        private void sendBody(
                int index,
                Http.Request request,
                OutputStream out,
                MessageDigest digest,
                AtomicLong sent,
                AtomicReference<IOException> failure) {
            SplittableRandom random = new SplittableRandom(connectionSeed(options, index));
            byte[] chunk = new byte[CHUNK];
            try {
                long left = request.bodyBytes();
                while (left > 0) {
                    int n = (int) Math.min(chunk.length, left);
                    random.nextBytes(chunk);
                    digest.update(chunk, 0, n);
                    if (request.chunked()) {
                        out.write((Integer.toHexString(n) + "\r\n").getBytes(StandardCharsets.US_ASCII));
                    }
                    out.write(chunk, 0, n);
                    if (request.chunked()) {
                        out.write(Http.CRLF);
                    }
                    sent.addAndGet(n);
                    left -= n;
                }
                if (request.chunked()) {
                    out.write(Http.LAST_CHUNK);
                }
                out.flush();
            } catch (IOException e) {
                failure.set(e);
            }
        }

        /** A stalled response: {@code GET /stall}, read up to the end of its head, then never read again. */
        private void stall(long deadline, Stalls stalls) {
            Socket raw = new Socket();
            connections.track(raw);
            boolean counted = false;
            boolean open = false;
            try {
                Socket socket = mode.target().open(raw, deadline);
                connections.opened();
                open = true;
                OutputStream out = socket.getOutputStream();
                out.write(STALL.head(mode.target().authority()));
                out.flush();
                Http.Head head = new Http.Reader(socket.getInputStream(), 1024).head();
                if (head.status() != 200) {
                    stalls.failed(head.describe(), false);
                    counted = true;
                    return;
                }
                stalls.connected();
                counted = true;
                connections.awaitStop();
            } catch (IOException e) {
                if (!connections.stopping()) {
                    stalls.failed(e.getClass().getSimpleName(), counted);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                if (open) {
                    connections.closed();
                }
                stalls.closed();
                connections.release(raw);
            }
        }

        /**
         * {@code GET /hello} every 100 ms, each on a new connection and timed from its connect to the end of its
         * body, once every stalled response has its head. A slow request delays the next one rather than bunching
         * the ones after it.
         */
        private void probe(Stalls stalls, long deadline, Probe probe) {
            try {
                stalls.awaitConnected(remaining(deadline));
            } catch (InterruptedException e) {
                return;
            }
            long next = System.nanoTime();
            while (!connections.stopping()) {
                Exchange exchange = exchange(-1, HELLO, HELLO_BODY, deadline);
                if (!exchange.matches()) {
                    if (!connections.stopping()) {
                        probe.fail(exchange.completed() ? "BodyMismatch" : exchange.failure());
                    }
                    return;
                }
                probe.record(exchange.completionNanos());
                next = Math.max(next + PROBE_INTERVAL_NANOS, System.nanoTime());
                sleepUntil(next);
            }
        }
    }

    /** A response body's length and SHA-256. */
    record Body(long bytes, String sha256) {
        static Body of(byte[] content) {
            MessageDigest digest = StreamLoad.sha256();
            digest.update(content);
            return new Body(content.length, hex(digest));
        }
    }

    /**
     * The outcome of one HTTP request; {@code failure} is null when a {@code 200} response came back whole. Times are
     * from the start of the connect.
     */
    record Exchange(
            int index,
            String failure,
            long connectNanos,
            long headNanos,
            long completionNanos,
            long sent,
            long received,
            Body expected,
            String receivedHash) {

        static Exchange failed(int index, String failure) {
            return new Exchange(index, failure, -1, -1, -1, 0, 0, null, null);
        }

        boolean completed() {
            return failure == null;
        }

        boolean matches() {
            return completed() && received == expected.bytes() && expected.sha256().equals(receivedHash);
        }

        /** Request body bytes per second, from the connect to the response head, which the echo sends once it has all. */
        double uploadMiBps() {
            return mibps(sent, headNanos - connectNanos);
        }

        double downloadMiBps() {
            return mibps(received, completionNanos - headNanos);
        }
    }

    /** The HTTP run's results, rendered as text or JSON. */
    record HttpReport(
            Options options,
            List<Exchange> exchanges,
            long requestNanos,
            long wallNanos,
            int peakConnected,
            Stalls stalls,
            Probe probe,
            boolean timedOut)
            implements Summary {

        private List<Exchange> present() {
            return exchanges.stream()
                    .map(e -> e != null ? e : Exchange.failed(-1, "TimedOut"))
                    .toList();
        }

        private List<Exchange> completed() {
            return present().stream().filter(Exchange::completed).toList();
        }

        int succeeded() {
            return (int) present().stream().filter(Exchange::matches).count();
        }

        List<Exchange> mismatches() {
            return present().stream()
                    .filter(e -> e.completed() && !e.matches())
                    .toList();
        }

        Map<String, Integer> failures() {
            Map<String, Integer> failures = new TreeMap<>();
            present().stream()
                    .filter(e -> !e.completed())
                    .forEach(e -> failures.merge(e.failure(), 1, Integer::sum));
            return failures;
        }

        @Override
        public boolean ok() {
            return !timedOut
                    && succeeded() == options.http().requests()
                    && (probe == null || probe.ok());
        }

        double requestsPerSecond() {
            return requestNanos > 0 ? succeeded() / (requestNanos / 1e9) : 0;
        }

        long uploaded() {
            return present().stream().mapToLong(Exchange::sent).sum();
        }

        long downloaded() {
            return present().stream().mapToLong(Exchange::received).sum();
        }

        double throughputMiBps() {
            return mibps(uploaded() + downloaded(), requestNanos);
        }

        double uploadMiBps() {
            return median(completed().stream().mapToDouble(Exchange::uploadMiBps).toArray());
        }

        double downloadMiBps() {
            return median(completed().stream().mapToDouble(Exchange::downloadMiBps).toArray());
        }

        private Percentiles times(ToLongFunction<Exchange> time) {
            return Percentiles.of(completed().stream().mapToLong(time).toArray());
        }

        @Override
        public String toText() {
            HttpMode mode = options.http();
            StringBuilder out = new StringBuilder();
            line(out, "result", ok() ? "OK" : "FAILED" + (timedOut ? " (timed out)" : ""));
            line(out, "seed", Long.toString(options.seed()));
            line(out, "request", mode.request().method() + " " + mode.request().describe());
            line(
                    out,
                    "requests",
                    "%d of %d succeeded, %d checksum mismatches, failed %s"
                            .formatted(succeeded(), mode.requests(), mismatches().size(), failures()));
            for (Exchange e : mismatches()) {
                line(
                        out,
                        "  mismatch",
                        "request %d: expected %d bytes (%s), received %d bytes (%s)"
                                .formatted(
                                        e.index(),
                                        e.expected().bytes(),
                                        e.expected().sha256(),
                                        e.received(),
                                        e.receivedHash()));
            }
            line(out, "peak open", Integer.toString(peakConnected));
            line(
                    out,
                    "rate",
                    "%.1f requests/s (%d in %.2f s)".formatted(requestsPerSecond(), succeeded(), requestNanos / 1e9));
            line(out, "connect", times(Exchange::connectNanos).toText());
            line(out, "head", times(Exchange::headNanos).toText());
            line(out, "completion", times(Exchange::completionNanos).toText());
            if (mode.request().bodyBytes() > 0) {
                line(
                        out,
                        "upload",
                        "%.2f MiB/s per request (p50), %s in all"
                                .formatted(uploadMiBps(), Sizes.format(uploaded())));
            }
            if (mode.request().bodyBytes() > 0 || mode.request().download() >= 0) {
                line(
                        out,
                        "download",
                        "%.2f MiB/s per request (p50), %s in all"
                                .formatted(downloadMiBps(), Sizes.format(downloaded())));
            }
            line(
                    out,
                    "throughput",
                    "%.2f MiB/s (%s both ways in %.2f s)"
                            .formatted(
                                    throughputMiBps(), Sizes.format(uploaded() + downloaded()), requestNanos / 1e9));
            if (options.stall() > 0) {
                line(
                        out,
                        "stalled",
                        "%d of %d got their heads, failed %s"
                                .formatted(stalls.opened.get(), options.stall(), new TreeMap<>(stalls.failures)));
            }
            if (probe != null) {
                line(out, "probe", probe.toText());
            }
            line(out, "wall time", "%.2f s".formatted(wallNanos / 1e9));
            return out.toString();
        }

        @Override
        public String toJson() {
            HttpMode mode = options.http();
            Map<String, Object> json = new LinkedHashMap<>();
            json.put("ok", ok());
            json.put("timedOut", timedOut);
            json.put("seed", options.seed());
            json.put("url", mode.target().scheme() + "://" + mode.target().authority());
            json.put("method", mode.request().method());
            json.put("path", mode.request().path());
            json.put("bytes", mode.request().bodyBytes());
            json.put("download", mode.request().download());
            json.put("chunked", mode.request().chunked());
            json.put("streams", options.streams());
            json.put("requests", mode.requests());
            json.put("stall", options.stall());
            json.put("succeeded", succeeded());
            json.put("mismatches", mismatches().stream().map(Exchange::index).toList());
            json.put("failed", failures());
            json.put("peakOpen", peakConnected);
            json.put("requestsPerSecond", round(requestsPerSecond()));
            json.put("connectMs", times(Exchange::connectNanos).toJson());
            json.put("headMs", times(Exchange::headNanos).toJson());
            json.put("completionMs", times(Exchange::completionNanos).toJson());
            json.put("uploadMiBps", round(uploadMiBps()));
            json.put("downloadMiBps", round(downloadMiBps()));
            json.put("uploadedBytes", uploaded());
            json.put("downloadedBytes", downloaded());
            json.put("requestSeconds", round(requestNanos / 1e9));
            json.put("throughputMiBps", round(throughputMiBps()));
            if (options.stall() > 0) {
                Map<String, Object> stalled = new LinkedHashMap<>();
                stalled.put("opened", stalls.opened.get());
                stalled.put("failed", new TreeMap<>(stalls.failures));
                json.put("stalled", stalled);
            }
            if (probe != null) {
                json.put("probeMs", probe.toJson());
            }
            json.put("wallSeconds", round(wallNanos / 1e9));
            return Json.write(json) + System.lineSeparator();
        }
    }

    /** Just enough HTTP/1.1 for {@code http-echo-server.py}: one request per connection, written and read by hand. */
    static final class Http {
        static final byte[] CRLF = {'\r', '\n'};
        static final byte[] LAST_CHUNK = "0\r\n\r\n".getBytes(StandardCharsets.US_ASCII);

        /** Where requests go: the URL's scheme and host, with {@code authority} as the {@code host} header. */
        record Target(String scheme, String host, int port, String authority) {

            static Target of(String url) {
                URI uri;
                try {
                    uri = new URI(url);
                } catch (URISyntaxException e) {
                    throw new IllegalArgumentException("--url is not a URL: " + url);
                }
                String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
                if (!scheme.equals("http") && !scheme.equals("https")) {
                    throw new IllegalArgumentException("--url must be http:// or https://: " + url);
                }
                if (uri.getHost() == null
                        || uri.getRawUserInfo() != null
                        || uri.getRawQuery() != null
                        || uri.getRawFragment() != null
                        || !(uri.getRawPath().isEmpty() || uri.getRawPath().equals("/"))) {
                    throw new IllegalArgumentException(
                            "--url is the tunnel's base, scheme://host[:port] with no path: " + url);
                }
                int defaultPort = scheme.equals("https") ? 443 : 80;
                int port = uri.getPort() < 0 ? defaultPort : uri.getPort();
                String host = uri.getHost().toLowerCase(Locale.ROOT);
                String authority = port == defaultPort ? host : host + ":" + port;
                return new Target(scheme, host.replaceAll("^\\[|]$", ""), port, authority);
            }

            /**
             * Connects {@code socket}, and over {@code https} returns the TLS socket layered on it. Names under {@code
             * localhost} go to the loopback address whatever the resolver says, as browsers and curl send them.
             */
            Socket open(Socket socket, long deadline) throws IOException {
                InetAddress address = host.equals("localhost") || host.endsWith(".localhost")
                        ? InetAddress.getLoopbackAddress()
                        : InetAddress.getByName(host);
                socket.setTcpNoDelay(true);
                socket.connect(new InetSocketAddress(address, port), connectTimeoutMillis(deadline));
                if (!scheme.equals("https")) {
                    return socket;
                }
                SSLSocket tls = (SSLSocket)
                        ((SSLSocketFactory) SSLSocketFactory.getDefault()).createSocket(socket, host, port, true);
                SSLParameters parameters = tls.getSSLParameters();
                parameters.setEndpointIdentificationAlgorithm("HTTPS");
                tls.setSSLParameters(parameters);
                tls.startHandshake();
                return tls;
            }
        }

        /** The request every connection of a run makes; {@code download} is -1 unless it asks for {@code /bytes}. */
        record Request(String method, String path, long bodyBytes, boolean chunked, long download) {

            Request(String method, String path, long bodyBytes, boolean chunked) {
                this(method, path, bodyBytes, chunked, -1);
            }

            static Request echo(long bytes, boolean chunked) {
                return new Request("POST", "/echo", bytes, chunked);
            }

            static Request download(long bytes, boolean chunked) {
                return new Request("GET", "/bytes/" + bytes + (chunked ? "?chunked=1" : ""), 0, false, bytes);
            }

            static Request hello() {
                return new Request("GET", "/hello", 0, false);
            }

            byte[] head(String authority) {
                StringBuilder head = new StringBuilder()
                        .append(method)
                        .append(' ')
                        .append(path)
                        .append(" HTTP/1.1\r\n")
                        .append("host: ")
                        .append(authority)
                        .append("\r\n")
                        .append("user-agent: StreamLoad\r\n")
                        .append("connection: close\r\n");
                if (bodyBytes > 0) {
                    head.append("content-type: application/octet-stream\r\n");
                    head.append(chunked ? "transfer-encoding: chunked\r\n" : "content-length: " + bodyBytes + "\r\n");
                }
                return head.append("\r\n").toString().getBytes(StandardCharsets.US_ASCII);
            }

            String describe() {
                if (bodyBytes > 0) {
                    return path + ", " + Sizes.format(bodyBytes) + " each" + (chunked ? ", chunked" : "");
                }
                return path;
            }
        }

        /** A final response head: its status and its fields, names in lower case. */
        record Head(int status, Map<String, List<String>> fields) {

            String field(String name) {
                List<String> values = fields.get(name);
                return values == null ? null : values.getFirst();
            }

            /** The failure a non-{@code 200} response counts as: its status, and the edge's error code if any. */
            String describe() {
                String code = field("jburrow-error");
                return "HTTP " + status + (code != null ? " " + code : "");
            }
        }

        /** Reads one response from a connection, through a buffer of its own. */
        static final class Reader {
            private static final int MAX_LINE = 16 * 1024;

            private final InputStream in;
            private final byte[] buffer;
            private int position;
            private int limit;

            Reader(InputStream in, int bufferSize) {
                this.in = in;
                this.buffer = new byte[bufferSize];
            }

            /** The final response head, skipping any {@code 1xx} before it. */
            Head head() throws IOException {
                while (true) {
                    String statusLine = line();
                    if (!statusLine.startsWith("HTTP/1.") || statusLine.length() < 12) {
                        throw new ProtocolException("not an HTTP/1.x status line");
                    }
                    int status;
                    try {
                        status = Integer.parseInt(statusLine.substring(9, 12));
                    } catch (NumberFormatException e) {
                        throw new ProtocolException("not an HTTP/1.x status line");
                    }
                    Map<String, List<String>> fields = new LinkedHashMap<>();
                    for (String field = line(); !field.isEmpty(); field = line()) {
                        int colon = field.indexOf(':');
                        if (colon <= 0) {
                            throw new ProtocolException("a header field without a name");
                        }
                        fields.computeIfAbsent(
                                        field.substring(0, colon).toLowerCase(Locale.ROOT), name -> new ArrayList<>())
                                .add(field.substring(colon + 1).trim());
                    }
                    if (status >= 200 || status == 101) {
                        return new Head(status, fields);
                    }
                }
            }

            /** Reads the body {@code head} announces into {@code digest} and returns its length. */
            long body(Head head, MessageDigest digest) throws IOException {
                if (head.status() == 204 || head.status() == 304) {
                    return 0;
                }
                String transferEncoding = head.field("transfer-encoding");
                if (transferEncoding != null) {
                    if (!transferEncoding.equalsIgnoreCase("chunked")) {
                        throw new ProtocolException("a transfer coding other than chunked");
                    }
                    return chunked(digest);
                }
                String contentLength = head.field("content-length");
                if (contentLength != null) {
                    long length;
                    try {
                        length = Long.parseLong(contentLength);
                    } catch (NumberFormatException e) {
                        throw new ProtocolException("content-length is not a number");
                    }
                    copy(length, digest);
                    return length;
                }
                return rest(digest);
            }

            private long chunked(MessageDigest digest) throws IOException {
                long total = 0;
                while (true) {
                    String sizeLine = line();
                    int extension = sizeLine.indexOf(';');
                    long size;
                    try {
                        size = Long.parseLong(
                                (extension < 0 ? sizeLine : sizeLine.substring(0, extension)).trim(), 16);
                    } catch (NumberFormatException e) {
                        throw new ProtocolException("a chunk size that isn't hexadecimal");
                    }
                    if (size == 0) {
                        while (!line().isEmpty()) {
                            // Trailer fields: read past them.
                        }
                        return total;
                    }
                    copy(size, digest);
                    total += size;
                    if (!line().isEmpty()) {
                        throw new ProtocolException("a chunk longer than its size");
                    }
                }
            }

            private void copy(long length, MessageDigest digest) throws IOException {
                long left = length;
                while (left > 0) {
                    if (position == limit && !fill()) {
                        throw new EOFException("the connection ended " + left + " bytes before the body did");
                    }
                    int n = (int) Math.min(left, limit - position);
                    digest.update(buffer, position, n);
                    position += n;
                    left -= n;
                }
            }

            private long rest(MessageDigest digest) throws IOException {
                long total = 0;
                while (position < limit || fill()) {
                    digest.update(buffer, position, limit - position);
                    total += limit - position;
                    position = limit;
                }
                return total;
            }

            private String line() throws IOException {
                StringBuilder line = new StringBuilder();
                while (true) {
                    if (position == limit && !fill()) {
                        throw new EOFException("the connection ended in a response head");
                    }
                    char c = (char) (buffer[position++] & 0xff);
                    if (c == '\n') {
                        int end = line.length();
                        return end > 0 && line.charAt(end - 1) == '\r' ? line.substring(0, end - 1) : line.toString();
                    }
                    if (line.length() == MAX_LINE) {
                        throw new ProtocolException("a line longer than 16 KiB");
                    }
                    line.append(c);
                }
            }

            private boolean fill() throws IOException {
                int n = in.read(buffer);
                if (n < 0) {
                    return false;
                }
                position = 0;
                limit = n;
                return true;
            }
        }
    }

    /**
     * Python's {@code random.Random(n).randbytes}, called in the 64 KiB pieces {@code http-echo-server.py} serves
     * {@code /bytes/<n>} in, so a download is checked without a reference copy. CPython's MT19937: seeded through
     * {@code init_by_array} with {@code n}'s 32-bit words, and each piece read as whole words, little-endian, the
     * last one cut to its top bytes.
     */
    static final class PythonRandom {
        private static final int N = 624;
        private static final int M = 397;

        private final int[] mt = new int[N];
        private int index;

        PythonRandom(long seed) {
            int[] key = seed >>> 32 == 0 ? new int[] {(int) seed} : new int[] {(int) seed, (int) (seed >>> 32)};
            mt[0] = 19650218;
            for (int i = 1; i < N; i++) {
                mt[i] = 1812433253 * (mt[i - 1] ^ (mt[i - 1] >>> 30)) + i;
            }
            int i = 1;
            int j = 0;
            for (int k = Math.max(N, key.length); k > 0; k--) {
                mt[i] = (mt[i] ^ ((mt[i - 1] ^ (mt[i - 1] >>> 30)) * 1664525)) + key[j] + j;
                i++;
                j++;
                if (i >= N) {
                    mt[0] = mt[N - 1];
                    i = 1;
                }
                if (j >= key.length) {
                    j = 0;
                }
            }
            for (int k = N - 1; k > 0; k--) {
                mt[i] = (mt[i] ^ ((mt[i - 1] ^ (mt[i - 1] >>> 30)) * 1566083941)) - i;
                i++;
                if (i >= N) {
                    mt[0] = mt[N - 1];
                    i = 1;
                }
            }
            mt[0] = 0x80000000;
            index = N;
        }

        /** The SHA-256 of {@code /bytes/<length>}. */
        static String sha256(long length) {
            PythonRandom random = new PythonRandom(length);
            MessageDigest digest = StreamLoad.sha256();
            byte[] piece = new byte[CHUNK];
            for (long left = length; left > 0; ) {
                int n = (int) Math.min(CHUNK, left);
                random.randbytes(piece, n);
                digest.update(piece, 0, n);
                left -= n;
            }
            return hex(digest);
        }

        void randbytes(byte[] out, int length) {
            int i = 0;
            for (; i + 4 <= length; i += 4) {
                int word = next();
                out[i] = (byte) word;
                out[i + 1] = (byte) (word >>> 8);
                out[i + 2] = (byte) (word >>> 16);
                out[i + 3] = (byte) (word >>> 24);
            }
            int rest = length - i;
            if (rest > 0) {
                int word = next() >>> (32 - 8 * rest);
                for (int b = 0; b < rest; b++) {
                    out[i + b] = (byte) (word >>> (8 * b));
                }
            }
        }

        private int next() {
            if (index >= N) {
                for (int k = 0; k < N; k++) {
                    int y = (mt[k] & 0x80000000) | (mt[(k + 1) % N] & 0x7fffffff);
                    mt[k] = mt[(k + M) % N] ^ (y >>> 1) ^ ((y & 1) != 0 ? 0x9908b0df : 0);
                }
                index = 0;
            }
            int y = mt[index++];
            y ^= y >>> 11;
            y ^= (y << 7) & 0x9d2c5680;
            y ^= (y << 15) & 0xefc60000;
            y ^= y >>> 18;
            return y;
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

    private static void line(StringBuilder out, String label, String value) {
        out.append(String.format(Locale.ROOT, "%-12s %s%n", label, value));
    }

    private static double mibps(long bytes, long nanos) {
        return nanos > 0 ? bytes / 1048576.0 / (nanos / 1e9) : 0;
    }

    private static double median(double[] values) {
        if (values.length == 0) {
            return 0;
        }
        double[] sorted = values.clone();
        Arrays.sort(sorted);
        return sorted[(sorted.length - 1) / 2];
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

    /** The HTTP mode's options: where requests go, which request each is, and how many in all. */
    record HttpMode(Http.Target target, Http.Request request, int requests) {}

    /** The run's options; {@code http} is null for a TCP tunnel, and {@code host} and {@code port} unset for HTTP. */
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
            boolean json,
            HttpMode http) {

        static final String USAGE =
                """
                Usage: java scripts/load/StreamLoad.java --host <host> --port <port> [options]
                       java scripts/load/StreamLoad.java --http --url <base> [options]

                Without --http: opens concurrent connections to a TCP tunnel in front of an echo service
                (scripts/echo-server.py), sends seeded random bytes on each while reading the echo, and
                checks every SHA-256.

                With --http: makes requests to an HTTP tunnel in front of scripts/http-echo-server.py, each
                on a connection of its own, and checks every response body's SHA-256.

                  --host <host>        the TCP tunnel's public host (required without --http)
                  --port <port>        the TCP tunnel's public port (required without --http)
                  --http               drive an HTTP tunnel instead
                  --url <base>         the HTTP tunnel's URL, http[s]://host[:port] (required with --http)
                  --streams <n>        concurrent transfer connections, or requests in flight (default 100)
                  --requests <n>       --http: requests in all (default: --streams)
                  --bytes <size>       bytes each transfer sends and expects back (default 1MiB); with --http,
                                       the body each POST /echo sends, and 0 makes each request GET /hello
                  --download <size>    --http: GET /bytes/<size> instead, checked against the echo server's
                                       pseudo-random stream
                  --chunked            --http: send request bodies chunked, and ask for chunked downloads
                  --ramp <duration>    spread transfer opens over this long (default 0: all at once; TCP only)
                  --stall <n>          extra connections that never read: TCP, they upload continuously; HTTP,
                                       each makes GET /stall and stops reading after the response head
                  --probe              TCP: one connection echoing 64 bytes every 100 ms, each round trip timed;
                                       HTTP: GET /hello every 100 ms on a new connection, each timed
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
            Long bytes = null;
            Duration ramp = null;
            int stall = 0;
            boolean probe = false;
            Duration duration = Duration.ZERO;
            Duration timeout = Duration.ofSeconds(300);
            long seed = ThreadLocalRandom.current().nextLong();
            boolean json = false;
            boolean http = false;
            String url = null;
            Integer requests = null;
            Long download = null;
            boolean chunked = false;
            List<String> rest = new ArrayList<>(List.of(args));
            while (!rest.isEmpty()) {
                String option = rest.removeFirst();
                switch (option) {
                    case "--help", "-h" -> {
                        return null;
                    }
                    case "--host" -> host = value(option, rest);
                    case "--port" -> port = intValue(option, rest, 1, 65535);
                    case "--http" -> http = true;
                    case "--url" -> url = value(option, rest);
                    case "--streams" -> streams = intValue(option, rest, 0, 1_000_000);
                    case "--requests" -> requests = intValue(option, rest, 0, 100_000_000);
                    case "--bytes" -> bytes = Sizes.parse(value(option, rest));
                    case "--download" -> download = Sizes.parse(value(option, rest));
                    case "--chunked" -> chunked = true;
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

            HttpMode httpMode = null;
            int work = streams;
            if (http) {
                if (url == null) {
                    throw new IllegalArgumentException("--http needs --url");
                }
                if (host != null || port >= 0 || ramp != null) {
                    throw new IllegalArgumentException("--host, --port and --ramp are for TCP tunnels; --http takes --url");
                }
                if (download != null && bytes != null) {
                    throw new IllegalArgumentException("--bytes and --download are two kinds of request; give one");
                }
                int total = requests != null ? requests : streams;
                if (total > 0 && streams == 0) {
                    throw new IllegalArgumentException("--requests needs at least one of --streams to make them");
                }
                long body = bytes != null ? bytes : 1L << 20;
                Http.Request request = download != null
                        ? Http.Request.download(download, chunked)
                        : body > 0 ? Http.Request.echo(body, chunked) : Http.Request.hello();
                httpMode = new HttpMode(Http.Target.of(url), request, total);
                work = total;
            } else {
                if (url != null || requests != null || download != null || chunked) {
                    throw new IllegalArgumentException("--url, --requests, --download and --chunked need --http");
                }
                if (host == null || port < 0) {
                    throw new IllegalArgumentException("--host and --port are required");
                }
            }
            if (work == 0 && stall == 0 && !probe) {
                throw new IllegalArgumentException("nothing to do: --streams, --stall and --probe are all off");
            }
            if (work == 0 && duration.isZero()) {
                throw new IllegalArgumentException("--streams 0 needs a --duration to hold the run open for");
            }
            if (duration.compareTo(timeout) >= 0) {
                throw new IllegalArgumentException("--duration must be shorter than --timeout");
            }
            return new Options(
                    host,
                    port,
                    streams,
                    bytes != null ? bytes : 1L << 20,
                    ramp != null ? ramp : Duration.ZERO,
                    stall,
                    probe,
                    duration,
                    timeout,
                    seed,
                    json,
                    httpMode);
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
