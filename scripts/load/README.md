# StreamLoad: the many-streams load driver

`StreamLoad.java` opens concurrent connections to a TCP tunnel whose local side is an echo service,
sends seeded random bytes on each while reading the echo, and checks every SHA-256. It is the
instrument for phase 2's exit criteria and for the measurements behind
[`docs/phase-2-multiplexing/ARCHITECTURE.md`](../../docs/phase-2-multiplexing/ARCHITECTURE.md)
§Performance and §Memory budget. Tests prove correctness; this measures cost.

It is a single-file Java 21 program that uses only the JDK, so there is nothing to build, and it is
not part of the Maven reactor or CI. It is run by hand.

## Running it

```bash
# 1. The echo service the tunnel forwards to
python3 scripts/echo-server.py 9000 &

# 2. A tunnel to it (local server from `make up && make run`)
JBURROW_AUTHTOKEN=jb_LOCAL-DEVELOPMENT-ONLY-unlocks-localhost-00 \
  java -jar jburrow-cli/target/jburrow-cli-*.jar tcp 9000 --server ws://localhost:7080/v1/connect
# Forwarding  tcp://localhost:21037 -> localhost:9000

# 3. The load: 500 streams, 1 MiB each way, through that one session
java scripts/load/StreamLoad.java --host localhost --port 21037 --streams 500 --bytes 1MiB
```

| Option | Default | Meaning |
|---|---|---|
| `--host`, `--port` | required | the tunnel's public address |
| `--streams` | `100` | concurrent connections doing the transfer |
| `--bytes` | `1MiB` | bytes each connection sends and expects back |
| `--ramp` | `0` | spread transfer opens over this long; `0` opens them all at once |
| `--stall` | `0` | extra connections that upload continuously and never read: the head-of-line load |
| `--probe` | off | one connection sending 64-byte messages every 100 ms and timing each echo; starts once every stalled connection is open |
| `--duration` | `0` | keep the stalled connections and the probe running until at least this long after the start, even once the transfers are done; needed with `--streams 0` |
| `--timeout` | `300s` | the whole run fails past this |
| `--seed` | random | seed for the random bytes |
| `--json` | off | the summary as one JSON object on stdout, for pasting into evidence |

Sizes are `64`, `256KiB`, `1MiB`, `2GiB`; durations `0`, `500ms`, `30s`, `5m`.

Each transfer connection runs on two virtual threads, one writing and one reading, so a full send
buffer never blocks the read. Connection `i`'s bytes come from a generator seeded from `--seed` and
`i`, and the seed is printed first, so a checksum mismatch can be replayed exactly with `--seed`.

## What it reports

- transfers succeeded, failed (by exception class; `TimedOut` for those cut by `--timeout`) and
  checksum mismatches, each mismatch with its connection index, sizes and both hashes;
- `peak open`: the most connections open at once, transfers, stalled and probe together;
- connect latency and completion time (connect to verified echo), p50, p99 and max;
- throughput: bytes both ways across all transfers ÷ the time until the last one finished;
- with `--stall`: how many opened and how much they uploaded before the run cut them;
- with `--probe`: echo round trip p50, p99, max, the sample count and how many took over 1 s.

Exit status is 0 only if every transfer connection (and the probe, if on) got its bytes back
intact before the timeout; 1 otherwise; 2 for a usage error. Stalled connections are cut when the
run ends and don't count.

**Checking the driver itself.** A copy of `echo-server.py` that flips one bit per chunk
(`sendall(chunk[:-1] + bytes([chunk[-1] ^ 1]))`) must make every transfer a mismatch and the exit
status 1.

## Scenarios

The six scenarios checkpoint 08 of phase 2 ran, and what each one decides. Results and decisions are
recorded in ARCHITECTURE.md §Performance, §Memory budget and §Open questions.

| # | Scenario | Run | Decides |
|---|---|---|---|
| 1 | Many streams | `--streams 500 --bytes 1MiB` | Baseline: does 500 work, how fast |
| 2 | Isolation | `--streams 1 --bytes 1MiB --stall N --probe --duration 30s` for N in 0, 1, 2, 20 | Probe latency beside stalled readers: where head-of-line stops being contained |
| 3 | Round trip | `--streams 1 --bytes 64MiB` under `tc qdisc add dev lo root netem delay Dms` for D in 0, 10, 25, 50, 100 (round trip 2D) | Measured throughput against window ÷ round trip (ARCHITECTURE.md §Choosing the windows): whether auto-tuning is needed |
| 4 | Stalled visitors' kernel memory | `--streams 0 --stall 1000 --duration 90s`; after 60 s, `ss -tmn state established '( sport >= :21000 and sport <= :21099 )'` summing each socket's `skmem`, and the server's RSS | Whether visitor socket buffers need `SO_SNDBUF` / `SO_RCVBUF` at `max-streams` |
| 5 | CPU of pausing | `tc qdisc add dev lo root netem rate 20mbit`, then `--streams 1000 --bytes 2MiB`, sampling server CPU each second | Server CPU per MiB/s when the session is limited by its client's link |
| 6 | Heap per stream | `--streams 0 --stall 1000 --duration 90s`, `jcmd <pid> GC.heap_info` and `GC.class_histogram` against the idle server | Heap per open stream, and whether the heap/direct split holds at `max-streams` |

**`tc` without root.** `tc` needs `CAP_NET_ADMIN`. Without `sudo`, run the whole stack (a bridge to
Postgres, the server, the echo service, the CLI and the driver) inside an unprivileged network
namespace (`unshare -rn`) and shape that namespace's own `lo`; nothing outside it is affected. With
`sudo` on the host's `lo`, everything on loopback is shaped, including the database container's
traffic: run with nothing else going, and always `tc qdisc del dev lo root` afterwards (a leftover
qdisc makes the next `make verify` very slow and very confusing).

**Reading `ss` memory.** In `skmem:(r…,rb…,t…,tb…)`, `r` and `t` are the bytes the kernel holds in
the socket's receive and send queues, `rb` and `tb` their current limits. `r` and `t` are what
cgroup v2 charges to the pod.
