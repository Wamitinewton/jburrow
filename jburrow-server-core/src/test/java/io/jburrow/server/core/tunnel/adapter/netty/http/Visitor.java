package io.jburrow.server.core.tunnel.adapter.netty.http;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A visitor speaking HTTP/1.1 to the edge over a plain socket, so a test controls every byte it sends and sees every
 * byte of the response, framing included.
 */
final class Visitor implements AutoCloseable {

    /** A response as the visitor read it: the status line's code, the fields in order, and the decoded body. */
    record Response(int status, List<String[]> headers, byte[] body) {

        String header(String name) {
            for (String[] field : headers) {
                if (field[0].equals(name)) {
                    return field[1];
                }
            }
            return null;
        }

        List<String> all(String name) {
            List<String> values = new ArrayList<>();
            for (String[] field : headers) {
                if (field[0].equals(name)) {
                    values.add(field[1]);
                }
            }
            return values;
        }

        String text() {
            return new String(body, StandardCharsets.UTF_8);
        }
    }

    private final Socket socket;
    private final InputStream in;
    private final OutputStream out;
    private final int port;
    private String host;

    Visitor(int port, String host, int receiveBuffer) throws IOException {
        this.socket = new Socket();
        if (receiveBuffer > 0) {
            socket.setReceiveBufferSize(receiveBuffer);
        }
        socket.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), port));
        socket.setSoTimeout((int) HttpHarness.TIMEOUT.toMillis());
        this.in = socket.getInputStream();
        this.out = socket.getOutputStream();
        this.port = port;
        this.host = host;
    }

    int localPort() {
        return socket.getLocalPort();
    }

    /** Later requests name {@code host}, at the edge's port, as a connection the ingress reuses does. */
    Visitor host(String host) {
        this.host = host + ":" + port;
        return this;
    }

    /** {@code method target} with a {@code Host} field and {@code fields}, a name then its value, and no body. */
    void request(String method, String target, String... fields) throws IOException {
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
        send(head.append("\r\n").toString());
    }

    /** {@code method target} with a body of known length. */
    void request(String method, String target, byte[] body, String... fields) throws IOException {
        String[] all = new String[fields.length + 2];
        System.arraycopy(fields, 0, all, 0, fields.length);
        all[fields.length] = "Content-Length";
        all[fields.length + 1] = Integer.toString(body.length);
        request(method, target, all);
        send(body);
    }

    void send(String text) throws IOException {
        send(text.getBytes(StandardCharsets.ISO_8859_1));
    }

    void send(byte[] bytes) throws IOException {
        out.write(bytes);
        out.flush();
    }

    /** One chunk of a chunked body; an empty one ends it. */
    void chunk(byte[] bytes) throws IOException {
        send(Integer.toHexString(bytes.length) + "\r\n");
        send(bytes);
        send("\r\n");
    }

    /** The next response, its body framed as its head says. A response to {@code HEAD} has no body. */
    Response response(boolean toHead) throws IOException {
        String[] lines = readHead().split("\r\n");
        int status = Integer.parseInt(lines[0].split(" ")[1]);
        List<String[]> headers = new ArrayList<>();
        for (int i = 1; i < lines.length; i++) {
            int colon = lines[i].indexOf(':');
            headers.add(new String[] {
                lines[i].substring(0, colon).toLowerCase(Locale.ROOT),
                lines[i].substring(colon + 1).strip()
            });
        }
        Response head = new Response(status, headers, new byte[0]);
        if (toHead || status < 200 || status == 204 || status == 304) {
            return head;
        }
        byte[] body;
        if ("chunked".equals(head.header("transfer-encoding"))) {
            body = readChunked();
        } else if (head.header("content-length") != null) {
            body = in.readNBytes(Integer.parseInt(head.header("content-length")));
        } else {
            body = in.readAllBytes();
        }
        return new Response(status, headers, body);
    }

    Response response() throws IOException {
        return response(false);
    }

    /** Whether the edge has closed the connection: the next read finds its end. */
    boolean closedByEdge() throws IOException {
        try {
            return in.read() < 0;
        } catch (IOException reset) {
            return true;
        }
    }

    private String readHead() throws IOException {
        ByteArrayOutputStream head = new ByteArrayOutputStream();
        while (!head.toString(StandardCharsets.ISO_8859_1).endsWith("\r\n\r\n")) {
            int b = in.read();
            if (b < 0) {
                throw new EOFException("The edge closed the connection inside a response head");
            }
            head.write(b);
        }
        return head.toString(StandardCharsets.ISO_8859_1);
    }

    private byte[] readChunked() throws IOException {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        while (true) {
            int size = Integer.parseInt(readLine(), 16);
            if (size == 0) {
                readLine();
                return body.toByteArray();
            }
            body.write(in.readNBytes(size));
            readLine();
        }
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
                return new String(bytes, 0, bytes.length - 1, StandardCharsets.ISO_8859_1);
            }
            line.write(b);
            previous = b;
        }
    }

    @Override
    public void close() throws IOException {
        socket.close();
    }
}
