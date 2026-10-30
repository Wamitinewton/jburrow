package io.jburrow.server.e2e.support;

import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * One HTTP/1.1 connection to the edge over a plain socket, so a test controls every byte it sends and reads every byte
 * of the response, framing included. One request at a time, each naming its own host, as a connection the ingress
 * keeps alive does.
 *
 * <p>{@linkplain #asIngress As the ingress}, it adds {@code X-Forwarded-For} and {@code X-Forwarded-Proto: https} to
 * every request, for a visitor whose address it names; the test's loopback address is then in
 * {@code jburrow.edge.trusted-proxies}, so the edge believes them.
 *
 * <p>Bodies can be read and written as streams, so a transfer of any size holds no more than a buffer.
 */
public final class HttpVisitor implements AutoCloseable {

    /** The ingress's own client address for requests it forwards: a documentation address no test uses otherwise. */
    public static final String VISITOR_ADDRESS = "203.0.113.9";

    private static final int PIECE = 64 * 1024;

    /** A response head as read: the status, and each field with its name in lower case, in order. */
    public record Head(int status, List<String[]> fields) {

        public Optional<String> header(String name) {
            return fields.stream()
                    .filter(field -> field[0].equals(name))
                    .map(field -> field[1])
                    .findFirst();
        }

        public List<String> all(String name) {
            return fields.stream()
                    .filter(field -> field[0].equals(name))
                    .map(field -> field[1])
                    .toList();
        }

        public boolean chunked() {
            return header("transfer-encoding").filter("chunked"::equals).isPresent();
        }
    }

    /** A whole response, its body decoded from its framing. */
    public record Response(Head head, byte[] body) {

        public int status() {
            return head.status();
        }

        public Optional<String> header(String name) {
            return head.header(name);
        }

        public String text() {
            return new String(body, UTF_8);
        }
    }

    private final Socket socket;
    private final InputStream in;
    private final OutputStream out;
    private String forwardedFor;

    private HttpVisitor(Socket socket) throws IOException {
        this.socket = socket;
        this.in = socket.getInputStream();
        this.out = socket.getOutputStream();
    }

    /** A connection to the edge on loopback {@code port}, reads bounded by {@code timeout}. */
    public static HttpVisitor connect(int port, Duration timeout) throws IOException {
        return connect(null, port, 0, timeout);
    }

    /**
     * A connection to the edge on loopback {@code port} from {@code localAddress}, or the system's choice when it is
     * null, with a receive buffer of {@code receiveBuffer} bytes, or the system's when it is 0.
     */
    public static HttpVisitor connect(InetAddress localAddress, int port, int receiveBuffer, Duration timeout)
            throws IOException {
        Socket socket = new Socket();
        try {
            if (receiveBuffer > 0) {
                socket.setReceiveBufferSize(receiveBuffer);
            }
            socket.setTcpNoDelay(true);
            if (localAddress != null) {
                socket.bind(new InetSocketAddress(localAddress, 0));
            }
            socket.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), (int) timeout.toMillis());
            socket.setSoTimeout((int) timeout.toMillis());
            return new HttpVisitor(socket);
        } catch (IOException e) {
            socket.close();
            throw e;
        }
    }

    /** From now on, sends each request as the ingress forwards one from {@link #VISITOR_ADDRESS}. */
    public HttpVisitor asIngress() {
        return asIngress(VISITOR_ADDRESS);
    }

    /** From now on, sends each request as the ingress forwards one from {@code visitorAddress}. */
    public HttpVisitor asIngress(String visitorAddress) {
        this.forwardedFor = visitorAddress;
        return this;
    }

    public int localPort() {
        return socket.getLocalPort();
    }

    public void timeout(Duration timeout) throws IOException {
        socket.setSoTimeout((int) timeout.toMillis());
    }

    // Requests.

    /** Sends {@code method target} to {@code host} with {@code fields}, each a name then its value, and no body. */
    public void request(String method, String host, String target, String... fields) throws IOException {
        send(head(method, host, target, fields));
    }

    /** Sends {@code method target} to {@code host} with a body of known length. */
    public void request(String method, String host, String target, byte[] body, String... fields) throws IOException {
        send(head(method, host, target, with(fields, "Content-Length", Integer.toString(body.length))));
        send(body);
    }

    /**
     * Sends the head of {@code method target} to {@code host} with a body of {@code length} bytes, or a chunked body
     * when {@code length} is negative, and returns the stream to write the body to. Closing it ends a chunked body and
     * leaves the connection open.
     */
    public OutputStream requestBody(String method, String host, String target, long length, String... fields)
            throws IOException {
        if (length < 0) {
            send(head(method, host, target, with(fields, "Transfer-Encoding", "chunked")));
            return new ChunkedOutputStream(out);
        }
        send(head(method, host, target, with(fields, "Content-Length", Long.toString(length))));
        return new FilterOutputStream(out) {
            @Override
            public void write(byte[] bytes, int offset, int length) throws IOException {
                out.write(bytes, offset, length);
            }

            @Override
            public void close() throws IOException {
                flush();
            }
        };
    }

    /** The head of {@code method target} with {@code fields} as raw HTTP/1.1, the ingress's fields included. */
    private String head(String method, String host, String target, String... fields) {
        StringBuilder head = new StringBuilder()
                .append(method)
                .append(' ')
                .append(target)
                .append(" HTTP/1.1\r\nHost: ")
                .append(host)
                .append("\r\n");
        for (int i = 0; i < fields.length; i += 2) {
            head.append(fields[i]).append(": ").append(fields[i + 1]).append("\r\n");
        }
        if (forwardedFor != null) {
            head.append("X-Forwarded-For: ").append(forwardedFor).append("\r\n");
            head.append("X-Forwarded-Proto: https\r\n");
        }
        return head.append("\r\n").toString();
    }

    private static String[] with(String[] fields, String name, String value) {
        String[] all = new String[fields.length + 2];
        System.arraycopy(fields, 0, all, 0, fields.length);
        all[fields.length] = name;
        all[fields.length + 1] = value;
        return all;
    }

    /** Raw bytes, as given: for requests no well-behaved client would send. */
    public void send(String text) throws IOException {
        send(text.getBytes(ISO_8859_1));
    }

    public void send(byte[] bytes) throws IOException {
        out.write(bytes);
        out.flush();
    }

    // Responses.

    /** The next whole response, its body read into memory. A response to {@code HEAD} has none. */
    public Response response(boolean toHead) throws IOException {
        Head head = head();
        if (toHead || !hasBody(head)) {
            return new Response(head, new byte[0]);
        }
        try (InputStream body = body(head)) {
            return new Response(head, body.readAllBytes());
        }
    }

    public Response response() throws IOException {
        return response(false);
    }

    /** The next final response's head, skipping any {@code 1xx}. */
    public Head finalHead() throws IOException {
        Head head = head();
        while (head.status() < 200) {
            head = head();
        }
        return head;
    }

    /** The next response head, informational ones included. */
    public Head head() throws IOException {
        String[] lines = readHead().split("\r\n");
        int status = Integer.parseInt(lines[0].split(" ")[1]);
        List<String[]> fields = new ArrayList<>();
        for (int i = 1; i < lines.length; i++) {
            int colon = lines[i].indexOf(':');
            fields.add(new String[] {
                lines[i].substring(0, colon).toLowerCase(Locale.ROOT),
                lines[i].substring(colon + 1).strip()
            });
        }
        return new Head(status, fields);
    }

    /**
     * The body that follows {@code head}, decoded from its framing as it is read. Closing it leaves the connection
     * open; a body delimited by the connection's end reads to that end.
     */
    public InputStream body(Head head) {
        if (!hasBody(head)) {
            return InputStream.nullInputStream();
        }
        if (head.chunked()) {
            return new ChunkedInputStream(in);
        }
        Optional<String> length = head.header("content-length");
        if (length.isPresent()) {
            return new BoundedInputStream(in, Long.parseLong(length.get()));
        }
        return new BoundedInputStream(in, Long.MAX_VALUE);
    }

    private static boolean hasBody(Head head) {
        return head.status() >= 200 && head.status() != 204 && head.status() != 304;
    }

    /** Whether the edge has closed the connection: the next read finds its end, or a reset. */
    public boolean closedByEdge() throws IOException {
        try {
            return in.read() < 0;
        } catch (SocketTimeoutException stillOpen) {
            return false;
        } catch (IOException reset) {
            return true;
        }
    }

    private String readHead() throws IOException {
        ByteArrayOutputStream head = new ByteArrayOutputStream();
        int matched = 0;
        while (matched < 4) {
            int b = in.read();
            if (b < 0) {
                throw new EOFException("The edge closed the connection before or inside a response head");
            }
            head.write(b);
            if ((b == '\r' && matched % 2 == 0) || (b == '\n' && matched % 2 == 1)) {
                matched++;
            } else {
                matched = b == '\r' ? 1 : 0;
            }
        }
        return head.toString(ISO_8859_1);
    }

    @Override
    public void close() throws IOException {
        socket.close();
    }

    /** At most {@code length} bytes of {@code in}; closing it doesn't close {@code in}. */
    private static final class BoundedInputStream extends InputStream {

        private final InputStream in;
        private long left;

        BoundedInputStream(InputStream in, long length) {
            this.in = in;
            this.left = length;
        }

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            return read(one, 0, 1) < 0 ? -1 : one[0] & 0xff;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            if (left == 0) {
                return -1;
            }
            int read = in.read(buffer, offset, (int) Math.min(length, left));
            if (read < 0) {
                if (left == Long.MAX_VALUE) {
                    left = 0;
                    return -1;
                }
                throw new EOFException("The edge closed the connection with " + left + " bytes of the body to come");
            }
            if (left != Long.MAX_VALUE) {
                left -= read;
            }
            return read;
        }

        @Override
        public void close() {}
    }

    /** A chunked body, decoded; its end is the empty chunk and the trailer section. Closing it doesn't close {@code in}. */
    private static final class ChunkedInputStream extends InputStream {

        private final InputStream in;
        private long chunkLeft;
        private boolean ended;

        ChunkedInputStream(InputStream in) {
            this.in = in;
        }

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            return read(one, 0, 1) < 0 ? -1 : one[0] & 0xff;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            if (ended) {
                return -1;
            }
            if (chunkLeft == 0) {
                String size = readLine();
                int extension = size.indexOf(';');
                chunkLeft = Long.parseLong(extension < 0 ? size : size.substring(0, extension), 16);
                if (chunkLeft == 0) {
                    while (!readLine().isEmpty()) {
                        // Trailer fields, which the edge never sends.
                    }
                    ended = true;
                    return -1;
                }
            }
            int read = in.read(buffer, offset, (int) Math.min(length, chunkLeft));
            if (read < 0) {
                throw new EOFException("The edge closed the connection inside a chunk");
            }
            chunkLeft -= read;
            if (chunkLeft == 0 && !readLine().isEmpty()) {
                throw new IOException("A chunk's data isn't followed by CRLF");
            }
            return read;
        }

        private String readLine() throws IOException {
            ByteArrayOutputStream line = new ByteArrayOutputStream();
            int previous = -1;
            while (true) {
                int b = in.read();
                if (b < 0) {
                    throw new EOFException("The edge closed the connection inside a chunked body");
                }
                if (previous == '\r' && b == '\n') {
                    byte[] bytes = line.toByteArray();
                    return new String(bytes, 0, bytes.length - 1, ISO_8859_1);
                }
                line.write(b);
                previous = b;
            }
        }

        @Override
        public void close() {}
    }

    /** Writes each write as one chunk; closing it writes the last chunk and leaves the connection open. */
    private static final class ChunkedOutputStream extends OutputStream {

        private final OutputStream out;

        ChunkedOutputStream(OutputStream out) {
            this.out = out;
        }

        @Override
        public void write(int b) throws IOException {
            write(new byte[] {(byte) b}, 0, 1);
        }

        @Override
        public void write(byte[] bytes, int offset, int length) throws IOException {
            for (int at = offset; at < offset + length; at += PIECE) {
                int size = Math.min(PIECE, offset + length - at);
                out.write((Integer.toHexString(size) + "\r\n").getBytes(ISO_8859_1));
                out.write(bytes, at, size);
                out.write("\r\n".getBytes(ISO_8859_1));
            }
        }

        @Override
        public void flush() throws IOException {
            out.flush();
        }

        @Override
        public void close() throws IOException {
            out.write("0\r\n\r\n".getBytes(ISO_8859_1));
            out.flush();
        }
    }
}
