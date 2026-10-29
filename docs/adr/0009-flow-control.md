# 9. Flow control

- Status: accepted
- Date: 2026-10-26

## Context

Phase 1 carried one stream per session, and its only backpressure was the connection itself: when a
visitor couldn't take more, the server stopped reading the client's WebSocket, and when the client's
local service couldn't take more, the client stopped reading the server's. With one stream that is
correct. With many it breaks in two ways (`docs/phase-2-multiplexing/ARCHITECTURE.md` §Head-of-line
blocking and deadlock):

- **Head-of-line blocking.** One visitor that stops reading a download stops the connection, and
  every other stream on the session stalls behind it, an SSH session included.
- **Deadlock.** An application that won't read connection A until it has written to connection B
  hangs for good once A's backlog pauses the connection B's bytes travel on.

Phase 2 raises the limit from 1 stream per session to 1,000. Three forces shape the replacement:

- **Server memory must be bounded per session, whatever the stream count.** The pod is limited to
  1 GiB with 256 MiB of direct memory, shared by up to 200 sessions (ADR-0008). Whatever a session
  can make the server hold has to be set by configuration.
- **A slow destination must hold back only its own stream**, in both directions.
- **Both sides must apply identical rules.** A rule the two sides disagree on shows up much later as
  a stream that stalls after a few hundred resets, or a peer killed for an overrun it didn't make.
  Third-party clients must be able to implement the same rules from the specification.

## Decision

- **Credit windows per stream and per session**, as in HTTP/2 and QUIC. A sender may send `DATA` of
  `n` bytes only when `n` is at most both its stream credit and its session credit, and takes `n`
  from both. Only `DATA` is flow-controlled, so a sender with no credit can still close, reset,
  answer a `PING` and return credit. The session window caps what a session can make its receiver
  hold, independent of the number of streams.
- **Each side announces its own receive windows** (`windows.stream`, `windows.session`) in its
  `HELLO`, since a window protects the receiver's memory. A stream window is at least 64 KiB, one
  full `DATA` payload, so no peer can force the other into byte-at-a-time work; a session window is
  at least its stream window. The reference server announces 256 KiB and 512 KiB, the client 256 KiB
  and 1 MiB.
- **Windows are fixed for the session.** There is no mid-session change of the initial windows
  (HTTP/2's `SETTINGS_INITIAL_WINDOW_SIZE`), so a window never goes negative and no open stream is
  adjusted retroactively. A receiver that wants a larger window grants extra credit.
- **Credit is returned for released bytes, at half the window.** A byte is released when the
  receiver no longer holds it: written to its destination socket, successfully or not, or
  discarded. A receiver must return credit once its unreturned released bytes reach half the window
  (rounded down), per stream and per session. `DATA` for a closed or reset stream still counts
  against the session window and is released at once, so resets never leak session credit. No stream
  credit goes back once the peer has half-closed the stream.
- **Every overrun is a session error**, `FLOW_CONTROL_ERROR` (close code 1002): `DATA` past either
  window, or a `WINDOW_UPDATE` that takes a credit past 2^31 − 1. A correct sender can't overrun a
  window under any timing, since the receiver's view of the sender's credit only grows when it sends
  an update and the sender's only grows when the update arrives. So an overrun is always a bug, and
  ADR-0007's rule for bugs is to fail at the first bad frame.
- **The accounting is pure Java in the protocol model**, `io.jburrow.protocol.flow`, shared by both
  sides like `StreamLifecycle` and `Liveness`. `SessionFlow` is the only way to change a credit or a
  window; adapters ask how much they may send and what to send back, and carry out the answer. The
  stream lifecycle gains `WINDOW_UPDATE_RECEIVED` and `WINDOW_UPDATE_SENT`, and stays the authority
  on stream state; flow sits next to it in each stream's entry. A Jazzer target fuzzes two peers
  built on the model for overruns, unbounded holding, credit conservation and deadlock.
- **Sources are read only as far as they can be sent.** A visitor (or local connection) is read only
  while `min(stream credit, session credit, session channel headroom, 64 KiB)` is above zero, and each
  read is capped at that amount, so nothing read is ever left unsent. When session credit runs out,
  visitors are paused lazily, the next time each is actually read. The exception to "nothing
  unsent" is the carry: on epoll a read can't be zero bytes without meaning end of input, so a
  visitor read with no credit gives up at most one byte, which is held until credit returns. And
  epoll reads a source that has sent `FIN` to the end of its input at once, credit or not, so a
  visitor that half-closes while held back carries the rest of what it sent, at most its socket
  receive buffer, and sends it ahead of its `CLOSE` as credit returns.
- **Neither side stops reading the control connection for flow control.** Credit is withheld
  instead, per stream. Phase 1's `Backpressure` and the liveness handlers' `pause` and `resume` go.
  The liveness rules for a side that pauses stay in the specification for third-party peers.
- **Protocol draft 2.** A draft 1 peer sends `DATA` whenever it has bytes, which a draft 2 receiver
  treats as a session error, so this is a new subprotocol token, `jburrow.v1-draft.2`, not a
  capability. Before v1.0.0 a server speaks one draft, so the phase 2 server refuses draft 1 clients
  with `UNSUPPORTED_PROTOCOL_VERSION`.

## Consequences

- A slow visitor or local service holds back only its own stream, after one stream window. Two
  streams that depend on each other complete. The server's worst case per session is set by its
  session window and the session channel's watermarks, about 0.88 MiB at the defaults, whatever the
  stream count (`docs/phase-2-multiplexing/ARCHITECTURE.md` §Memory budget).
- One stream's throughput is at most its window per round trip: 256 KiB over 200 ms is 1.25 MiB/s.
  That is above every uplink phase 1 measured, but a fast uplink on a long path will hit it.
  Auto-tuning (granting a fast stream extra credit) is the fix, and needs no wire change.
- A memory-bounded receiver can always be filled by `session window / stream window` streams that
  never drain: two stalled visitors at the server's defaults stop that session's client-to-visitor
  direction. The other direction, other sessions and the server's memory are unaffected.
  Per-source visitor limits (phase 6) are the defence.
- Every phase 1 CLI stops working when the phase 2 server is deployed, so testers must get the new
  CLI first.
- Every adapter on both sides must release each received byte exactly once, on write completion or
  discard. A missed release stalls a stream; a double release hands the peer credit for memory still
  in use, which the model rejects with `IllegalStateException`. The tests must cover both.
- Kernel socket buffers are outside the windows, since credit returns when a write reaches the
  socket. Many stalled visitors can hold up to `tcp_wmem`'s maximum each in kernel memory charged to
  the pod. The load test measures it, and `SO_SNDBUF` is the lever if it has to be capped.
- Fairness between streams comes from reading each visitor at most once per event-loop turn, and
  from a line of visitors waiting for the session: when it is resumed, each may take an equal share
  of the credit in its first read, whatever order the transport reads them in, and a visitor keeps
  its place until a read of its own is sent, then rejoins at the back. No scheduler, and priorities
  would need one.

## Alternatives considered

- **Stream windows only** (yamux). Simpler, but what a session can make the receiver hold grows with
  its stream count: 1,000 streams × 256 KiB is 250 MiB from one client, the whole direct memory cap.
- **Returning credit when bytes arrive.** Updates would go out sooner, but a window would then bound
  nothing: a receiver whose destination stops reading would keep granting credit and buffering
  without limit.
- **A stream-window overrun as a stream error** (HTTP/2). It suggests an overrun can be benign, but
  no timing makes a correct sender overrun a window. Resetting one stream would keep a buggy peer
  connected and hide the bug.
- **Buffering unsent data behind a fair-queue scheduler** (Netty's HTTP/2 flow controller). Reading
  visitors freely and queueing frames until credit arrives needs a priority queue per session and a
  buffer bounded by the scheduler rather than by construction. Reading only what can be sent keeps
  the relay copy-free and stateless, at the cost of the one-byte carry.
- **Pausing the control connection** (phase 1). It is exactly what causes head-of-line blocking and
  the deadlock between dependent streams.
- **A connection per stream.** No multiplexing at all: each visitor would cost the client a new
  WebSocket with its own TLS handshake and ingress connection, and a session would no longer be one
  unit to register, drain and resume.
- **Window auto-tuning now.** It would lift the per-stream throughput limit on long paths, but nobody
  has hit that limit yet, and it adds a receiver policy that has to be tuned and tested against
  memory. The protocol already allows it (a receiver may grant more than it released), so it can come
  later with no wire change once the load test shows it is needed.
- **Mid-session window changes** (HTTP/2 `SETTINGS`). They make windows go negative and require
  retroactive adjustment of every open stream, one of the most error-prone parts of RFC 9113. Extra
  credit through `WINDOW_UPDATE` covers the same need.
