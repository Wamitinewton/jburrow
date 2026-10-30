# 12. HTTP exchanges across event loops: demand reads, reservations and parking

- Status: accepted
- Date: 2026-10-29

## Context

ADR-0008 confines a session to one event loop and pins every TCP visitor to it: the tunnel listener
is the session's, so its accepted channels register on the session's loop, and phase 2's read gate
can ask the flow model how much a visitor may read from inside the visitor's own read
(ADR-0009, ADR-0010). An HTTP exchange can't be pinned that way
(`docs/phase-3-http-tunnels/ARCHITECTURE.md` §Concurrency model):

- **The edge connection isn't the tunnel's.** It belongs to the ingress, which keeps a pool of
  keep-alive connections and reuses each for any host under the wildcard. It lives on whichever edge
  loop accepted it, and its next request may be for another session on another loop.
- **The read gate can't ask.** Phase 2 caps each read with a `RecvByteBufAllocator` that reads the
  stream's and the session's credit. That allocator runs on the edge loop; the flow state is on the
  session's loop and isn't thread-safe.
- **Paused readers are ordinary.** The server's session window is two stream windows (512 KiB
  against 256 KiB), so two streams that stop draining stop a session's client-to-visitor direction
  (ADR-0009, Consequences). With TCP that takes deliberate stalling. With HTTP a browser does it
  whenever it pauses a video or leaves a long download in a background tab, and two such responses
  would freeze every other request on the tunnel.
- **The kernel holds bytes the windows don't count.** A held-back upload sits in the edge
  connection's receive queue and a stalled response in its send queue, each up to the kernel's
  auto-tuned maximum, against the node's `tcp_mem` that other workloads share (ADR-0010).

## Decision

- **Each half of an exchange stays on its own loop.** The edge connection, its pipeline, its codec
  and its `autoRead` belong to the edge connection's loop. The stream's lifecycle, its `StreamFlow`,
  the session's `SessionFlow`, the exchange gate and parking belong to the session's loop.
  `ExchangeHandler` (edge side) and `SessionExchange` (session side) are the two halves; only
  `SessionExchange` calls the domain. Each posts a task to the other's loop with what it learnt
  ("read once, up to `n` bytes"; "this content arrived", "this write completed", "this read
  finished") and never reads the other's state. Response writes go the same way, as tasks posted in
  the order the session makes them, so a response's head, body and end can't reorder even when the
  session and the edge connection share a loop, where a direct write would overtake tasks already
  posted; the write's listener posts the release back.
- **Request bodies are read on demand, against reservations in the flow model.** On the session's
  loop, `SessionFlow.reserve(stream, max)` takes up to 16 KiB (the share floor of ADR-0010, capped
  by the session channel's headroom, which the caller folds into `max`) of stream and session credit
  at once. Reserved credit is spent as far as every other stream is concerned. A grant of `g > 0`
  posts one read of exactly `g` bytes to the edge loop; the decoded content, never larger than the
  bytes read, goes out as `DATA` with `onSentReserved`, and what the read didn't use comes back with
  `refund`. A grant of 0 waits in phase 2's line, exactly as a TCP visitor does. A stream that ends
  with a reservation refunds it at once (`refundAll`), which returns the session's part. The peer
  never learns of a reservation: `available + reserved + sent` equals its grant, and the
  `2^31 − 1` check on a `WINDOW_UPDATE` counts the reservation.
- **Stalled responses are parked, within a budget.** A response that holds bytes and has completed
  no write for `jburrow.http.park-after` (1 s) is parked if the server-wide parked total and its
  session's stay within `jburrow.http.max-parked` (16 MiB) and `jburrow.http.max-parked-per-session`
  (2 MiB). `SessionFlow.park(stream)` treats the stream's held bytes as released for the session
  window only and returns the session credit to send; the bytes stay queued to the edge channel, and
  the stream's own credit comes back only as they are written, so a parked stream never holds more
  than its stream window. Releases take parked bytes first, returning stream credit only for them.
  A stream that leaves its table with bytes parked is marked with `onRemoved` and keeps being
  released through its flow, so no parked byte returns session credit twice. This is credit
  returned before release, which `docs/protocol.md` §10.4 already allows: no wire change, and the
  client can't tell. The model knows nothing of time or budgets; the timer and `ParkingBudget` are
  the edge's.
- **Only HTTP streams park, and only the server parks or reserves.** The client's local reads keep
  phase 2's allocator, since its local connections are on its session's loop.
- **Socket buffers per kind.** An edge connection that carries an exchange gets
  `jburrow.http.socket-buffer` (16 KiB, 32 KiB once Linux doubles it) as `SO_SNDBUF` and
  `SO_RCVBUF`, bounding what an exchange can hold in the kernel to 64 KiB, phase 2's allowance per
  stream. A control session keeps the kernel's auto-tuning, which it needs across the internet.
- **A separate Service for tunnel traffic.** The wildcard ingress routes to its own ClusterIP Service
  on the same port, so the ingress pools tunnel connections apart from control ones, and an edge
  connection is in practice either a tunnel connection or a connect one.

## Consequences

- An exchange keeps every rule of ADR-0008: no state shared across loops, nothing blocking on
  either. The cost is a task per crossing, three hops per request (the head, at least one read, the
  end) and one per response write, far below the per-byte cost at 64 KiB writes.
- An upload goes in 16 KiB demands, one crossing each way per demand. Checkpoint 10 measures it
  against phase 2's TCP rate; on the in-node hop from the ingress it is expected to fill any
  tester's uplink.
- Nothing is read from a visitor without credit to send it, apart from one read of under 4 KiB that
  completes a head, so a slow upload can't tie up server memory.
- The server's memory bound per session moves from its session window to its session window plus
  its parked bytes, and the server-wide bound gains `max-parked` (16 MiB of direct memory). Past the
  budget, stalled responses behave as in phase 2; `jburrow_flow_park_refused_total` says when it
  binds. The budget is an estimate that checkpoint 10 measures with a paused video behind the
  ingress.
- `SessionFlow` gains `reserve`, `onSentReserved`, `refund`, `refundAll`, `park` and `onRemoved`.
  Each throws on misuse and changes nothing when it throws, and `SessionFlowFuzzTest` checks
  reservations, refunds and parking against conservation, bounded holding, bounded parking and
  no deadlock. Every adapter must refund every reservation once and release every byte once, as
  ADR-0009 already requires of releases.
- Small kernel buffers on tunnel connections cost nothing on the hop from the ingress, which is
  inside the node, but would cost throughput if a self-hoster put the edge across a slow link
  without an ingress. `0` leaves the setting to the kernel.

## Alternatives considered

- **Moving the edge channel to the session's loop for each exchange.** Netty can deregister and
  re-register a channel, but its pipeline would change loops between requests, half-finished work on
  the old loop would race the new one, and the next request on the same connection may be for
  another session.
- **An allocator that reads the flow state across threads.** It would keep phase 2's read gate
  unchanged, but the flow model isn't thread-safe, and making it so would put locks or atomics on
  every byte of every session to serve one kind of stream.
- **Buffering request bodies per stream.** Reading a body freely and queueing it until credit
  arrives needs a buffer per exchange bounded by policy rather than by construction, which is
  exactly what ADR-0009 rejected for the TCP relay.
- **A larger server session window.** It raises every session's worst case to buy room only stalled
  sessions use: 200 sessions × 2 MiB more is 400 MiB of direct memory.
- **Smaller stream windows for HTTP sessions.** 64 KiB, the protocol's minimum, moves the freeze from
  two stalled responses to eight and caps a download at 64 KiB per round trip, 0.64 MiB/s at 100 ms.
  Parking keeps 256 KiB windows and absorbs any number of stalls up to its budget.
- **Resetting a stalled stream.** It breaks a paused video, which a browser resumes by reading on.
- **Parking TCP streams too.** A TCP visitor that stalls is rare in honest use and deliberate
  otherwise, and the budget would go to whoever stalls first. Phase 6's per-source limits are the
  TCP answer. The model doesn't know a stream's kind, so opting TCP in later is a configuration
  change.
