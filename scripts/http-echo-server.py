#!/usr/bin/env python3
"""HTTP/1.1 service for the native smoke test, the load driver and trying HTTP tunnels by hand.

Usage: python3 scripts/http-echo-server.py <port>.

  GET  /hello                          200, "hello\\n"
  POST /echo                           200, the request body back with the same length; chunked
                                       if the request was chunked
  GET  /bytes/<n>[?chunked=1]          n bytes of a pseudo-random stream seeded by n, so every
                                       response for the same n is the same; chunked on request
  GET  /sse?count=<n>&interval=<ms>    text/event-stream, one event per interval, each flushed
  GET  /stall                          content-length 64 MiB, written until the socket blocks: a
                                       reader that stops reading holds the connection open

Python 3 standard library only. Every response body is read or written in 64 KiB pieces, except
/echo, which reads the whole request body before it answers.
"""

import random
import sys
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlsplit

PIECE = 64 * 1024
STALL_BYTES = 64 * 1024 * 1024


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    server_version = "http-echo-server"
    sys_version = ""

    def do_GET(self):
        url = urlsplit(self.path)
        query = parse_qs(url.query)
        if url.path == "/hello":
            self.send_body(200, b"hello\n", "text/plain")
        elif url.path.startswith("/bytes/") and url.path[len("/bytes/"):].isdigit():
            self.send_bytes(int(url.path[len("/bytes/"):]), query.get("chunked") == ["1"])
        elif url.path == "/sse":
            self.send_events(int(query.get("count", ["5"])[0]), int(query.get("interval", ["1000"])[0]))
        elif url.path == "/stall":
            self.send_stall()
        else:
            self.send_body(404, b"not found\n", "text/plain")

    def do_POST(self):
        if urlsplit(self.path).path != "/echo":
            self.drain()
            self.send_body(404, b"not found\n", "text/plain")
            return
        chunked = self.is_chunked()
        body = self.read_chunked() if chunked else self.rfile.read(self.content_length())
        content_type = self.headers.get("content-type", "application/octet-stream")
        if chunked:
            self.start_chunked(200, content_type)
            for offset in range(0, len(body), PIECE):
                self.write_chunk(body[offset:offset + PIECE])
            self.end_chunked()
        else:
            self.send_body(200, body, content_type)

    def send_body(self, status, body, content_type):
        self.send_response(status)
        self.send_header("content-type", content_type)
        self.send_header("content-length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def send_bytes(self, length, chunked):
        stream = random.Random(length)
        if chunked:
            self.start_chunked(200, "application/octet-stream")
        else:
            self.send_response(200)
            self.send_header("content-type", "application/octet-stream")
            self.send_header("content-length", str(length))
            self.end_headers()
        remaining = length
        while remaining > 0:
            piece = stream.randbytes(min(PIECE, remaining))
            remaining -= len(piece)
            if chunked:
                self.write_chunk(piece)
            else:
                self.wfile.write(piece)
        if chunked:
            self.end_chunked()

    def send_events(self, count, interval_ms):
        self.start_chunked(200, "text/event-stream")
        for index in range(count):
            if index > 0:
                time.sleep(interval_ms / 1000)
            self.write_chunk(f"id: {index}\ndata: event {index}\n\n".encode("ascii"))
            self.wfile.flush()
        self.end_chunked()

    def send_stall(self):
        self.send_response(200)
        self.send_header("content-type", "application/octet-stream")
        self.send_header("content-length", str(STALL_BYTES))
        self.end_headers()
        piece = bytes(PIECE)
        for _ in range(STALL_BYTES // PIECE):
            self.wfile.write(piece)

    def start_chunked(self, status, content_type):
        self.send_response(status)
        self.send_header("content-type", content_type)
        self.send_header("transfer-encoding", "chunked")
        if content_type == "text/event-stream":
            self.send_header("cache-control", "no-store")
        self.end_headers()
        self.wfile.flush()

    def write_chunk(self, data):
        if data:
            self.wfile.write(b"%x\r\n%b\r\n" % (len(data), data))

    def end_chunked(self):
        self.wfile.write(b"0\r\n\r\n")
        self.wfile.flush()

    def is_chunked(self):
        return "chunked" in self.headers.get("transfer-encoding", "").lower()

    def content_length(self):
        return int(self.headers.get("content-length", "0"))

    def read_chunked(self):
        body = bytearray()
        while True:
            size = int(self.rfile.readline().split(b";", 1)[0].strip(), 16)
            if size == 0:
                # Trailer fields, if any, up to the empty line.
                while self.rfile.readline() not in (b"\r\n", b"\n", b""):
                    pass
                return bytes(body)
            body += self.rfile.read(size)
            self.rfile.readline()

    def drain(self):
        if self.is_chunked():
            self.read_chunked()
        else:
            self.rfile.read(self.content_length())

    def log_message(self, format, *args):
        pass


class Server(ThreadingHTTPServer):
    allow_reuse_address = True
    daemon_threads = True
    # socketserver's default backlog is 5; the load driver and the smoke test open hundreds at once.
    request_queue_size = 1024


def main():
    if len(sys.argv) != 2:
        sys.exit("usage: http-echo-server.py <port>")
    port = int(sys.argv[1])
    with Server(("127.0.0.1", port), Handler) as server:
        print(f"http echo server listening on 127.0.0.1:{port}", flush=True)
        server.serve_forever()


if __name__ == "__main__":
    main()
