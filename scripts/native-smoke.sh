#!/usr/bin/env bash
# Runs a real tunnel through the native jburrow binary and checks the bytes that come back: 50
# concurrent streams on one session, then one 4 MiB stream.
#
# Needs: Docker, a JDK 21 on the PATH, python3, jq, curl, and both builds already in target/:
#   ./mvnw -pl jburrow-server,jburrow-cli -am package -DskipTests
#   ./mvnw -Pnative -pl jburrow-cli -am package -DskipTests
#
# The server runs from its JVM build with the local profile, on its own ports (edge :17080, API
# :17090, management :17091, tunnels 21100-21199) so it doesn't collide with `make run`.
# JBURROW_BIN runs another binary through the same scenario, for example a wrapper around the JVM
# CLI. Logs land in target/native-smoke/.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

JBURROW_BIN="${JBURROW_BIN:-jburrow-cli/target/jburrow}"
ECHO_PORT="${ECHO_PORT:-9000}"
EDGE_PORT=17080
API_PORT=17090
MANAGEMENT_PORT=17091
TUNNEL_PORTS="21100-21199"
EDGE_URL="ws://localhost:$EDGE_PORT/v1/connect"
READINESS_URL="http://localhost:$MANAGEMENT_PORT/actuator/health/readiness"
# The local profile's documented development token (CONTRIBUTING.md). It unlocks only a server on this machine.
AUTHTOKEN="jb_LOCAL-DEVELOPMENT-ONLY-unlocks-localhost-00"
# 256 KiB per stream passes half the stream window, so every stream has credit returned at least once.
CONCURRENT_STREAMS=50
CONCURRENT_BYTES=$((256 * 1024))
PAYLOAD_BYTES=$((4 * 1024 * 1024))

WORK="target/native-smoke"
SERVER_LOG="$WORK/server.log"
ECHO_LOG="$WORK/echo.log"
CLI_LOG="$WORK/cli.log"
EVENTS="$WORK/events.jsonl"

SERVER_PID=""
ECHO_PID=""
CLI_PID=""

log() { printf '==> %s\n' "$*"; }

fail() {
  printf 'native smoke test FAILED: %s\n' "$*" >&2
  exit 1
}

stop() {
  local pid="$1"
  if [[ -n "$pid" ]] && kill -0 "$pid" 2>/dev/null; then
    kill "$pid" 2>/dev/null || true
    wait "$pid" 2>/dev/null || true
  fi
}

cleanup() {
  local status=$?
  stop "$CLI_PID"
  stop "$ECHO_PID"
  stop "$SERVER_PID"
  if [[ $status -ne 0 ]]; then
    for file in "$EVENTS" "$CLI_LOG" "$ECHO_LOG" "$SERVER_LOG"; do
      [[ -f "$file" ]] || continue
      printf '\n----- %s (last 200 lines) -----\n' "$file" >&2
      tail -n 200 "$file" >&2
    done
  fi
  exit "$status"
}
trap cleanup EXIT

# wait_until <seconds> <what> <command...>: polls the command every 250 ms until it succeeds.
wait_until() {
  local seconds="$1" what="$2"
  shift 2
  local deadline=$((SECONDS + seconds))
  until "$@"; do
    ((SECONDS < deadline)) || fail "timed out after ${seconds}s waiting for $what"
    sleep 0.25
  done
}

alive() {
  local pid="$1" name="$2"
  kill -0 "$pid" 2>/dev/null || fail "$name exited early"
}

exited() {
  ! kill -0 "$1" 2>/dev/null
}

port_free() {
  ! (exec 3<>"/dev/tcp/127.0.0.1/$1") 2>/dev/null
}

server_ready() {
  alive "$SERVER_PID" "the server"
  curl -fsS -o /dev/null "$READINESS_URL" 2>/dev/null
}

echo_ready() {
  alive "$ECHO_PID" "the echo server"
  grep -q 'listening' "$ECHO_LOG"
}

registered() {
  alive "$CLI_PID" "the native CLI"
  grep -q '"type":"registered"' "$EVENTS"
}

events_of() {
  grep -c "\"type\":\"$1\"" "$EVENTS" || true
}

streams_closed() {
  alive "$CLI_PID" "the native CLI"
  (($(events_of stream_closed) >= $1))
}

# echo_through <port> <streams> <bytes>: opens the streams together, then sends each its own random
# bytes and reads them back, sending and receiving at once. Prints the sha256 of the first stream's
# bytes; fails naming every stream whose echo differs.
echo_through() {
  python3 - "$@" <<'PY'
import hashlib
import os
import socket
import sys
import threading

port, streams, size = int(sys.argv[1]), int(sys.argv[2]), int(sys.argv[3])
# Every connection is open before any sends, so the session carries all of them at once.
opened = threading.Barrier(streams)
results = [None] * streams


def stream(index):
    payload = os.urandom(size)
    try:
        sock = socket.create_connection(("127.0.0.1", port), timeout=60)
    except OSError as e:
        opened.abort()
        results[index] = f"connect failed: {e}"
        return
    with sock:
        try:
            opened.wait()
        except threading.BrokenBarrierError:
            results[index] = "another stream failed to connect"
            return
        errors = []

        def send():
            try:
                sock.sendall(payload)
                sock.shutdown(socket.SHUT_WR)
            except OSError as e:
                errors.append(e)

        sender = threading.Thread(target=send)
        sender.start()
        received = bytearray()
        try:
            while chunk := sock.recv(65536):
                received += chunk
        except OSError as e:
            errors.append(e)
        sender.join()
    sent_sha = hashlib.sha256(payload).hexdigest()
    received_sha = hashlib.sha256(received).hexdigest()
    if errors:
        results[index] = f"failed: {errors[0]}"
    elif sent_sha != received_sha:
        results[index] = f"sent {size} bytes ({sent_sha}), received {len(received)} bytes ({received_sha})"
    else:
        results[index] = sent_sha


threads = [threading.Thread(target=stream, args=(i,)) for i in range(streams)]
for t in threads:
    t.start()
for t in threads:
    t.join()
failed = [f"stream {i}: {r}" for i, r in enumerate(results) if len(r) != 64]
if failed:
    sys.exit(f"echo differs on {len(failed)} of {streams} streams:\n" + "\n".join(failed))
print(results[0])
PY
}

for tool in docker java python3 jq curl; do
  command -v "$tool" >/dev/null || fail "$tool is not on the PATH"
done
[[ -x "$JBURROW_BIN" ]] || fail "$JBURROW_BIN not found; build it with ./mvnw -Pnative -pl jburrow-cli -am package -DskipTests"
shopt -s nullglob
server_jars=(jburrow-server/target/jburrow-server-*.jar)
shopt -u nullglob
[[ ${#server_jars[@]} -eq 1 ]] || fail "expected one server JAR in jburrow-server/target, found ${#server_jars[@]}"
for port in "$EDGE_PORT" "$API_PORT" "$MANAGEMENT_PORT" "$ECHO_PORT"; do
  port_free "$port" || fail "port $port is in use"
done

rm -rf "$WORK"
mkdir -p "$WORK"

log "Starting Postgres"
docker compose up -d --wait postgres

log "Starting the server (${server_jars[0]})"
java -jar "${server_jars[0]}" \
  --server.port="$API_PORT" \
  --management.server.port="$MANAGEMENT_PORT" \
  --jburrow.edge.port="$EDGE_PORT" \
  --jburrow.tcp.port-range="$TUNNEL_PORTS" \
  >"$SERVER_LOG" 2>&1 &
SERVER_PID=$!
wait_until 60 "server readiness" server_ready

log "Starting the echo service on :$ECHO_PORT"
python3 scripts/echo-server.py "$ECHO_PORT" >"$ECHO_LOG" 2>&1 &
ECHO_PID=$!
wait_until 10 "the echo service" echo_ready

log "Opening a tunnel with $JBURROW_BIN"
# A script's background jobs start with SIGINT ignored, and neither the JVM nor a native image
# overrides an inherited ignore. Restore the default so SIGINT acts like Ctrl-C in a terminal.
JBURROW_AUTHTOKEN="$AUTHTOKEN" env --default-signal=INT \
  "$JBURROW_BIN" tcp "$ECHO_PORT" --server "$EDGE_URL" --output json >"$EVENTS" 2>"$CLI_LOG" &
CLI_PID=$!
wait_until 15 "the registered event" registered
public_port="$(jq -r 'select(.type == "registered") | .tunnels[0].publicPort' "$EVENTS")"
[[ "$public_port" =~ ^[0-9]+$ ]] || fail "no public port in the registered event"
log "Tunnel open on localhost:$public_port"

log "Sending $CONCURRENT_BYTES random bytes on each of $CONCURRENT_STREAMS concurrent streams"
echo_through "$public_port" "$CONCURRENT_STREAMS" "$CONCURRENT_BYTES" >/dev/null ||
  fail "the concurrent echo differs"
wait_until 15 "$CONCURRENT_STREAMS stream_closed events" streams_closed "$CONCURRENT_STREAMS"
opened_events="$(events_of stream_opened)"
closed_events="$(events_of stream_closed)"
[[ $opened_events -eq $CONCURRENT_STREAMS && $closed_events -eq $CONCURRENT_STREAMS ]] ||
  fail "expected $CONCURRENT_STREAMS stream_opened and stream_closed events, got $opened_events and $closed_events"
resets="$(grep '"type":"stream_closed"' "$EVENTS" | jq -r 'select(.reset != null) | "stream \(.streamId): \(.reset)"')"
[[ -z "$resets" ]] || fail "streams were reset: $resets"
log "All $CONCURRENT_STREAMS echoes match"

log "Sending $PAYLOAD_BYTES random bytes through the tunnel"
sent_sha="$(echo_through "$public_port" 1 "$PAYLOAD_BYTES")" || fail "the echo differs"
log "Echo matches (sha256 $sent_sha)"

log "Stopping the CLI with SIGINT"
kill -INT "$CLI_PID"
wait_until 10 "the CLI to exit after SIGINT" exited "$CLI_PID"
cli_status=0
wait "$CLI_PID" || cli_status=$?
CLI_PID=""
[[ $cli_status -eq 0 ]] || fail "the CLI exited with $cli_status after SIGINT, expected 0"
last_event="$(tail -n 1 "$EVENTS")"
[[ "$(jq -r '.type + " " + .reason' <<<"$last_event")" == "closed USER_CLOSED" ]] ||
  fail "the last event is not closed/USER_CLOSED: $last_event"

TIMEFORMAT='%3Rs'
startup="$({ time "$JBURROW_BIN" version >/dev/null; } 2>&1)"
echo
echo "native smoke test passed"
echo "  binary:   $JBURROW_BIN ($(du -h "$JBURROW_BIN" | cut -f1))"
echo "  version:  $("$JBURROW_BIN" version)"
echo "  start-up: $startup (jburrow version)"
