#!/usr/bin/env python3
"""TCP echo service for the native smoke test and the load driver.

Usage: python3 scripts/echo-server.py <port>.

Echoes every byte back on the same connection. After the peer half-closes, it shuts down its own
write side, so a client that half-closes after sending reads the full echo and then EOF.
"""

import socket
import socketserver
import sys


class EchoHandler(socketserver.BaseRequestHandler):
    def handle(self):
        while True:
            chunk = self.request.recv(65536)
            if not chunk:
                break
            self.request.sendall(chunk)
        self.request.shutdown(socket.SHUT_WR)


class EchoServer(socketserver.ThreadingTCPServer):
    allow_reuse_address = True
    daemon_threads = True
    # socketserver's default backlog is 5; the load driver opens hundreds of connections at once.
    request_queue_size = 1024


def main():
    if len(sys.argv) != 2:
        sys.exit("usage: echo-server.py <port>")
    port = int(sys.argv[1])
    with EchoServer(("127.0.0.1", port), EchoHandler) as server:
        print(f"echo server listening on 127.0.0.1:{port}", flush=True)
        server.serve_forever()


if __name__ == "__main__":
    main()
