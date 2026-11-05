#!/usr/bin/env bash
# Polls a URL and measures how long it stops answering: the gap a deploy or a restart leaves for a
# visitor of a tunnel. Each request gets 2 seconds. A line is printed, with the time, whenever the
# outcome changes: "ok" for a 2xx or 3xx, otherwise the status, "timeout", or "unreachable" for no
# HTTP answer at all. On Ctrl-C it prints the longest run of failures and when it started, counted
# from the first failed request to the first one that succeeded again (or to the Ctrl-C).
#
#   scripts/gap-probe.sh <url> [interval-seconds]     # interval default 0.25
#   scripts/gap-probe.sh http://quiet-otter-4f2a.localhost:7080/hello
#
# Needs curl and date (and sleep), nothing else.
set -uo pipefail

usage() {
  echo "usage: $0 <url> [interval-seconds]" >&2
  exit 2
}

[[ $# -ge 1 && $# -le 2 ]] || usage
URL="$1"
INTERVAL="${2:-0.25}"
[[ "$INTERVAL" =~ ^[0-9]+([.][0-9]+)?$ ]] || usage
command -v curl >/dev/null || { echo "gap-probe: curl is required" >&2; exit 2; }

# Milliseconds since the epoch: bash 5's clock, else GNU date, else whole seconds (BSD date has no %N).
now_ms() {
  if [[ -n "${EPOCHREALTIME:-}" ]]; then
    local micros="${EPOCHREALTIME/[.,]/}"
    echo $((10#$micros / 1000))
    return
  fi
  local millis
  millis="$(date +%s%3N)"
  if [[ "$millis" =~ ^[0-9]+$ ]]; then
    echo "$millis"
  else
    echo $(($(date +%s) * 1000))
  fi
}

# HH:MM:SS.mmm of a millisecond timestamp, local time; GNU date first, then BSD's.
clock() {
  local seconds=$(($1 / 1000)) millis=$(($1 % 1000)) hms
  hms="$(date -d "@$seconds" +%H:%M:%S 2>/dev/null || date -r "$seconds" +%H:%M:%S)"
  printf '%s.%03d' "$hms" "$millis"
}

# Seconds with one decimal, from milliseconds.
seconds() {
  printf '%d.%d' $(($1 / 1000)) $((($1 % 1000) / 100))
}

outcome() {
  local status code
  status="$(curl -s -o /dev/null -m 2 -w '%{http_code}' "$URL")"
  code=$?
  if [[ $code -eq 28 ]]; then
    echo timeout
  elif [[ "$status" =~ ^[23][0-9][0-9]$ ]]; then
    echo ok
  elif [[ "$status" =~ ^[1-9][0-9][0-9]$ ]]; then
    echo "$status"
  else
    echo unreachable
  fi
}

last=""
failing_since=""
longest=0
longest_from=""

end_failure_run() {
  local ended="$1"
  if [[ -n "$failing_since" ]]; then
    local run=$((ended - failing_since))
    if ((run > longest)); then
      longest=$run
      longest_from=$failing_since
    fi
    failing_since=""
  fi
}

report() {
  end_failure_run "$(now_ms)"
  echo
  if [[ -z "$longest_from" ]]; then
    echo "no failure"
  else
    echo "longest failure $(seconds "$longest") s from $(clock "$longest_from")"
  fi
  exit 0
}
trap report INT TERM

echo "probing $URL every ${INTERVAL}s; Ctrl-C for the longest failure"
while true; do
  started="$(now_ms)"
  result="$(outcome)"
  if [[ "$result" != "$last" ]]; then
    echo "$(clock "$started") $result"
    last="$result"
  fi
  if [[ "$result" == ok ]]; then
    end_failure_run "$started"
  elif [[ -z "$failing_since" ]]; then
    failing_since="$started"
  fi
  sleep "$INTERVAL"
done
