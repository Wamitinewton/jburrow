# 8. Byte-path concurrency model

- Status: accepted
- Date: 2026-10-25

## Context

JBurrow is one process (ADR-0002) that holds thousands of client sessions, each a long-lived
WebSocket, and relays every tunnel byte between a visitor's socket and its session. Four forces
shape how its threads work:

- **The relay must not copy or lock.** Every public byte crosses it. A copy per frame or a lock per
  write is paid on every byte, and contention grows with the number of sessions.
- **Some work blocks.** Token verification reads configuration in phase 1 and Postgres from phase
  5; port reservation and, later, lease writes go to the database too. A blocked I/O thread stalls
  every connection that shares it.
- **Memory is small and fixed.** The pod is limited to 1 GiB, the JVM to 75% of that, and direct
  memory to 256 MiB. Per-session memory has to be bounded by configuration, not by how fast a
  visitor sends or how slowly a client reads.
- **The session is a protocol state machine.** Stream lifecycle, liveness, registration and
  shutdown all touch it. If several threads can reach it, every one of those rules needs
  synchronisation, and every bug in it is a race.

The edge listener (`docs/phase-1-tcp-tunnel/ARCHITECTURE.md` §Concurrency model) is the first
code that runs on the byte path, so the model has to be settled before it is built. Every later
phase (multiplexing, HTTP tunnels, drain) builds on it.

## Decision

- **One event loop per session, for the session's whole life.** A session's channel is registered
  on one worker event loop when it is accepted and never moves. One TCP connection can't use more
  than one core usefully anyway; the WebSocket is the bottleneck, not the loop.
- **Tunnel listeners and visitor channels are bound on that same loop.** A tunnel's
  `ServerBootstrap` uses the session's event loop as both parent and child group, so every
  visitor connection to it is on the session's loop too. A byte from a visitor to the client is
  read, framed and written without crossing a thread, and backpressure between the two channels is
  a direct `setAutoRead` call with no race.
- **Session state is confined and unsynchronised.** The domain objects in `tunnel.domain` are
  documented as not thread-safe, and only their session's loop touches them. A `synchronized` or a
  concurrent collection in session state is a design error, not a fix.
- **Blocking work runs on a virtual-thread executor, and its result is re-checked on the loop.**
  The composition root creates one executor and hands it to the engine. A result returns through
  `eventLoop.execute`, and the session checks its state again before acting, since the client may
  have gone meanwhile: a registration that finishes for a closed session releases what it took.
- **Global state is only what has to be global**, each piece behind its own small
  synchronisation: the port pool (`synchronized`, touched at registration and teardown) and the
  admission counters (a CAS over packed counters and a `ConcurrentHashMap`). The relay touches
  neither.
- **epoll on Linux, NIO elsewhere, and always NIO on the client.** The server picks epoll when
  Netty's native library loads and NIO otherwise, logs the choice, and lets
  `jburrow.edge.transport` force either; forcing epoll where it is unavailable fails startup. The
  client stays on NIO, which behaves the same on every platform and under native-image (ADR-0006).
- **No io_uring.** Container runtimes' default seccomp profiles commonly block its system calls,
  and nothing measured at this scale shows a need for it.
- **A zero-copy relay with one owner per buffer.** A visitor read becomes a `DATA` payload without
  a copy (the header is a separate buffer in a composite), and a received `DATA` payload is a
  retained slice of its WebSocket message. Netty's rule applies everywhere: whoever holds a
  message last releases it, and a frame dropped for any reason is released at the point of the
  decision. Every test runs with the leak detector at `PARANOID` and fails on a leak.
- **Direct memory is capped by the deployment** (`-Dio.netty.maxDirectMemory`), so a leak fails
  loudly with an allocation error rather than growing until the pod is killed, and the edge
  exports what it uses.
- **Reads are at most 64 KiB**, the largest `DATA` payload, so one socket read is exactly one
  frame and one large body can't hold the connection.

## Consequences

- The session code is single-threaded and easy to reason about: state transitions are method
  calls, not protocols between threads, and their tests need no concurrency.
- A session can't use more than one core. That is accepted; a session is one TCP connection.
- A blocking call on an event loop stalls every session on that loop. It is a bug the tests must
  catch; if one ever slips through, Netty's `BlockHound` is the next step.
- Anything that leaves the loop comes back to a session that may have changed, so every
  asynchronous step re-checks state. That rule has to be kept in every later phase.
- Memory per session is bounded by the write-buffer watermarks and the 64 KiB frame, which is what
  `docs/phase-1-tcp-tunnel/ARCHITECTURE.md` §Memory budget adds up.

## Alternatives considered

- **A thread per connection on virtual threads.** Simple to write, but each stream needs two
  blocked threads, one per direction; backpressure becomes blocking writes with no way to pause a
  peer cleanly; and there is no control over buffer ownership, so the relay copies through heap
  arrays.
- **A shared executor for the relay.** Moving each frame to a pool costs a cross-thread handoff per
  frame and needs locks on session state, which is exactly what confinement avoids.
- **Visitor channels on any loop.** Leaving tunnel listeners on Netty's default group spreads
  visitors across loops, so every visitor-to-session write crosses threads and session state needs
  synchronisation again.
- **io_uring.** Rejected above: blocked by default seccomp profiles and not needed at this scale.
