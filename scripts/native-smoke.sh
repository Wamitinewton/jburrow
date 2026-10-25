#!/usr/bin/env bash
# Runs a real tunnel through the native jburrow binary and checks the bytes that come back.
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

log "Sending $PAYLOAD_BYTES random bytes through the tunnel"
head -c "$PAYLOAD_BYTES" /dev/urandom >"$WORK/sent.bin"
python3 - "$public_port" "$WORK/sent.bin" "$WORK/received.bin" <<'PY'
import socket
import sys
import threading

port, sent_path, received_path = int(sys.argv[1]), sys.argv[2], sys.argv[3]
with open(sent_path, "rb") as f:
    payload = f.read()

sock = socket.create_connection(("127.0.0.1", port), timeout=30)
send_error = []


def send():
    try:
        sock.sendall(payload)
        sock.shutdown(socket.SHUT_WR)
    except OSError as e:
        send_error.append(e)


# Send and receive at once: the echo comes back while bytes are still going out.
sender = threading.Thread(target=send)
sender.start()
with open(received_path, "wb") as out:
    while True:
        chunk = sock.recv(65536)
        if not chunk:
            break
        out.write(chunk)
sender.join()
sock.close()
if send_error:
    sys.exit(f"send failed: {send_error[0]}")
PY
sent_sha="$(sha256sum "$WORK/sent.bin" | cut -d' ' -f1)"
received_sha="$(sha256sum "$WORK/received.bin" | cut -d' ' -f1)"
[[ "$sent_sha" == "$received_sha" ]] ||
  fail "echo differs: sent $(stat -c %s "$WORK/sent.bin") bytes ($sent_sha), received $(stat -c %s "$WORK/received.bin") bytes ($received_sha)"
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
