#!/usr/bin/env bash
# Runs real tunnels through the native jburrow binary and checks the bytes that come back. A TCP
# tunnel: 50 concurrent streams on one session, then one 4 MiB stream. An HTTP tunnel: 50
# concurrent requests (25 uploads echoed back, every other one chunked, and 25 1 MiB downloads),
# then a server-sent event stream, then a short StreamLoad --http run so the load driver keeps working.
#
# Needs: Docker, a JDK 21 on the PATH, python3, jq, curl, and both builds already in target/:
#   ./mvnw -pl jburrow-server,jburrow-cli -am package -DskipTests
#   ./mvnw -Pnative -pl jburrow-cli -am package -DskipTests
#
# The server runs from its JVM build with the local profile, on its own ports (edge :17080, API
# :17090, management :17091, tunnels 21100-21199) so it doesn't collide with `make run`. HTTP
# tunnels are http://<name>.localhost:17080; curl is pointed at 127.0.0.1 for every name.
# JBURROW_BIN runs another binary through the same scenario, for example a wrapper around the JVM
# CLI. Logs land in target/native-smoke/.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

JBURROW_BIN="${JBURROW_BIN:-jburrow-cli/target/jburrow}"
ECHO_PORT="${ECHO_PORT:-9000}"
HTTP_ECHO_PORT="${HTTP_ECHO_PORT:-9001}"
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
HTTP_UPLOADS=25
HTTP_DOWNLOADS=25
UPLOAD_BYTES=$((256 * 1024))
DOWNLOAD_BYTES=$((1024 * 1024))
SSE_EVENTS=5

WORK="target/native-smoke"
SERVER_LOG="$WORK/server.log"
ECHO_LOG="$WORK/echo.log"
CLI_LOG="$WORK/cli.log"
EVENTS="$WORK/events.jsonl"
HTTP_ECHO_LOG="$WORK/http-echo.log"
HTTP_CLI_LOG="$WORK/http-cli.log"
HTTP_EVENTS="$WORK/http-events.jsonl"

SERVER_PID=""
ECHO_PID=""
CLI_PID=""
HTTP_ECHO_PID=""
HTTP_CLI_PID=""

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
  stop "$HTTP_CLI_PID"
  stop "$HTTP_ECHO_PID"
  stop "$SERVER_PID"
  if [[ $status -ne 0 ]]; then
    for file in "$EVENTS" "$CLI_LOG" "$ECHO_LOG" "$HTTP_EVENTS" "$HTTP_CLI_LOG" "$HTTP_ECHO_LOG" "$SERVER_LOG"; do
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

http_echo_ready() {
  alive "$HTTP_ECHO_PID" "the HTTP echo server"
  grep -q 'listening' "$HTTP_ECHO_LOG"
}

http_registered() {
  alive "$HTTP_CLI_PID" "the native CLI's HTTP tunnel"
  grep -q '"type":"registered"' "$HTTP_EVENTS"
}

# events_of <type> [file]: how many events of that type the file holds, by default the TCP run's.
events_of() {
  grep -c "\"type\":\"$1\"" "${2:-$EVENTS}" || true
}

streams_closed() {
  alive "$CLI_PID" "the native CLI"
  (($(events_of stream_closed) >= $1))
}

requests_reported() {
  alive "$HTTP_CLI_PID" "the native CLI's HTTP tunnel"
  (($(events_of request "$HTTP_EVENTS") >= $1))
}

# stop_cli <pid> <events>: stops the CLI with SIGINT, as Ctrl-C would, and expects exit status 0 and a
# final closed/USER_CLOSED event.
stop_cli() {
  local pid="$1" events="$2" cli_status=0 last_event
  kill -INT "$pid"
  wait_until 10 "the CLI to exit after SIGINT" exited "$pid"
  wait "$pid" || cli_status=$?
  [[ $cli_status -eq 0 ]] || fail "the CLI exited with $cli_status after SIGINT, expected 0"
  last_event="$(tail -n 1 "$events")"
  [[ "$(jq -r '.type + " " + .reason' <<<"$last_event")" == "closed USER_CLOSED" ]] ||
    fail "the last event is not closed/USER_CLOSED: $last_event"
}

# curl through the edge: every tunnel name under localhost goes to 127.0.0.1, whatever the resolver does.
tunnel_curl() {
  curl -fsS --max-time 60 --connect-to "::127.0.0.1:" "$@"
}

sha_of() {
  sha256sum "$1" | cut -d' ' -f1
}

# http_through <url>: the uploads and downloads, all started together, each body compared by SHA-256
# with what was sent or with the same download fetched straight from the echo server. Fails naming
# every request that failed or whose body differs.
http_through() {
  local url="$1" i pid failed=() pids=() names=()
  local reference="$WORK/http/reference.bin"
  mkdir -p "$WORK/http"
  curl -fsS -o "$reference" "http://127.0.0.1:$HTTP_ECHO_PORT/bytes/$DOWNLOAD_BYTES" ||
    fail "the echo server didn't serve /bytes/$DOWNLOAD_BYTES directly"
  for ((i = 0; i < HTTP_UPLOADS; i++)); do
    head -c "$UPLOAD_BYTES" /dev/urandom >"$WORK/http/upload-$i.bin"
  done

  for ((i = 0; i < HTTP_UPLOADS; i++)); do
    if ((i % 2 == 0)); then
      tunnel_curl --data-binary "@$WORK/http/upload-$i.bin" -o "$WORK/http/echoed-$i.bin" "$url/echo" &
    else
      tunnel_curl -H 'transfer-encoding: chunked' --data-binary "@$WORK/http/upload-$i.bin" \
        -o "$WORK/http/echoed-$i.bin" "$url/echo" &
    fi
    pids+=($!)
    names+=("upload $i")
  done
  for ((i = 0; i < HTTP_DOWNLOADS; i++)); do
    tunnel_curl -o "$WORK/http/download-$i.bin" "$url/bytes/$DOWNLOAD_BYTES" &
    pids+=($!)
    names+=("download $i")
  done
  for i in "${!pids[@]}"; do
    wait "${pids[$i]}" || failed+=("${names[$i]}: curl failed")
  done

  for ((i = 0; i < HTTP_UPLOADS; i++)); do
    [[ -f "$WORK/http/echoed-$i.bin" && "$(sha_of "$WORK/http/echoed-$i.bin")" == "$(sha_of "$WORK/http/upload-$i.bin")" ]] ||
      failed+=("upload $i: the echo differs")
  done
  for ((i = 0; i < HTTP_DOWNLOADS; i++)); do
    [[ -f "$WORK/http/download-$i.bin" && "$(sha_of "$WORK/http/download-$i.bin")" == "$(sha_of "$reference")" ]] ||
      failed+=("download $i: the body differs")
  done
  ((${#failed[@]} == 0)) || fail "$(printf '%s\n' "${#failed[@]} HTTP requests failed:" "${failed[@]}")"
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
for port in "$EDGE_PORT" "$API_PORT" "$MANAGEMENT_PORT" "$ECHO_PORT" "$HTTP_ECHO_PORT"; do
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
  --jburrow.http.public-port="$EDGE_PORT" \
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

log "Stopping the TCP tunnel with SIGINT"
stop_cli "$CLI_PID" "$EVENTS"
CLI_PID=""

log "Starting the HTTP echo service on :$HTTP_ECHO_PORT"
python3 scripts/http-echo-server.py "$HTTP_ECHO_PORT" >"$HTTP_ECHO_LOG" 2>&1 &
HTTP_ECHO_PID=$!
wait_until 10 "the HTTP echo service" http_echo_ready

log "Opening an HTTP tunnel with $JBURROW_BIN"
JBURROW_AUTHTOKEN="$AUTHTOKEN" env --default-signal=INT \
  "$JBURROW_BIN" http "$HTTP_ECHO_PORT" --server "$EDGE_URL" --output json >"$HTTP_EVENTS" 2>"$HTTP_CLI_LOG" &
HTTP_CLI_PID=$!
wait_until 15 "the registered event" http_registered
public_url="$(jq -r 'select(.type == "registered") | .tunnels[0].url' "$HTTP_EVENTS")"
[[ "$public_url" =~ ^http://[a-z0-9-]+\.localhost:$EDGE_PORT$ ]] || fail "no HTTP URL in the registered event: $public_url"
log "Tunnel open at $public_url"

http_requests=$((HTTP_UPLOADS + HTTP_DOWNLOADS))
log "Sending $HTTP_UPLOADS uploads of $UPLOAD_BYTES bytes and $HTTP_DOWNLOADS downloads of $DOWNLOAD_BYTES bytes at once"
http_through "$public_url"
log "All $http_requests bodies match"

log "Reading $SSE_EVENTS server-sent events"
sse="$(tunnel_curl -N "$public_url/sse?count=$SSE_EVENTS&interval=200")" || fail "the event stream failed"
sse_events="$(grep -c '^data: ' <<<"$sse" || true)"
[[ $sse_events -eq $SSE_EVENTS ]] || fail "expected $SSE_EVENTS events, got $sse_events: $sse"
log "All $SSE_EVENTS events arrived"

wait_until 15 "$((http_requests + 1)) request events" requests_reported $((http_requests + 1))
not_ok="$(jq -r 'select(.type == "request" and (.status != 200 or .reset != null))
  | "stream \(.streamId) \(.method) \(.target): status \(.status), reset \(.reset)"' "$HTTP_EVENTS")"
[[ -z "$not_ok" ]] || fail "requests didn't end with a complete 200: $not_ok"

log "Running the load driver through the HTTP tunnel: 10 echoes of 64 KiB"
java scripts/load/StreamLoad.java --http --url "$public_url" --streams 10 --bytes 64KiB >"$WORK/stream-load.txt" 2>&1 ||
  fail "$(printf '%s\n' "StreamLoad --http failed:" "$(cat "$WORK/stream-load.txt")")"

log "Stopping the HTTP tunnel with SIGINT"
stop_cli "$HTTP_CLI_PID" "$HTTP_EVENTS"
HTTP_CLI_PID=""

TIMEFORMAT='%3Rs'
startup="$({ time "$JBURROW_BIN" version >/dev/null; } 2>&1)"
echo
echo "native smoke test passed"
echo "  binary:   $JBURROW_BIN ($(du -h "$JBURROW_BIN" | cut -f1))"
echo "  version:  $("$JBURROW_BIN" version)"
echo "  start-up: $startup (jburrow version)"
