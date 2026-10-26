# Phase 2, Multiplexing and Flow Control: Architecture

Status: designed, not yet built. Third phase of `PROJECT.md` §Build roadmap, built on phase 1's TCP
tunnel and wire protocol.

`PROJECT.md` gives phase 2 one line: many streams per session, per-stream credit windows and
`WINDOW_UPDATE` on top of phase 1's session writability; done when 500 concurrent connections share
one session, and an end-to-end test with a deliberately slow client shows server heap and direct
memory staying flat. This document is the full design.

Phase 1 carried one stream per session and said why it couldn't carry more
([phase 1 §Backpressure in phase 1](../phase-1-tcp-tunnel/ARCHITECTURE.md#backpressure-in-phase-1)):
pausing the whole connection for one slow visitor stalls every other stream on it, and two streams
that depend on each other deadlock. Phase 2 removes that limit. It adds flow control to the protocol
as a second draft, `jburrow.v1-draft.2`, and changes how both sides apply backpressure. A slow
destination now holds back only its own stream, and the memory a session can make the server hold
stays the same whether one stream is open or a thousand.

Read first: `PROJECT.md` (§Backpressure, §The wire protocol, §Clean architecture rules),
[`docs/phase-1-tcp-tunnel/ARCHITECTURE.md`](../phase-1-tcp-tunnel/ARCHITECTURE.md) (everything this
phase changes is described there first, especially §Concurrency model, §The data path, §Backpressure
in phase 1 and §Memory budget), [`docs/protocol.md`](../protocol.md),
[ADR-0007](../adr/0007-wire-protocol-v1.md) and [ADR-0008](../adr/0008-byte-path-concurrency-model.md).

## Contents

- [Purpose](#purpose)
- [Goals and non-goals](#goals-and-non-goals)
- [Where this phase departs from PROJECT.md](#where-this-phase-departs-from-projectmd)
- [Runtime view](#runtime-view)
- [Protocol draft 2](#protocol-draft-2)
  - [Why a new draft](#why-a-new-draft)
  - [Announced windows](#announced-windows)
  - [Sending](#sending)
  - [Receiving](#receiving)
  - [Returning credit](#returning-credit)
  - [Violations](#violations)
  - [Windows and stream states](#windows-and-stream-states)
  - [What else changes in the specification](#what-else-changes-in-the-specification)
- [The flow control model](#the-flow-control-model)
- [Choosing the windows](#choosing-the-windows)
- [Module and package layout](#module-and-package-layout)
- [Concurrency model](#concurrency-model)
- [The server](#the-server)
  - [Admitting streams](#admitting-streams)
  - [Visitor to client: the read gate](#visitor-to-client-the-read-gate)
  - [Client to visitor: credit on delivery](#client-to-visitor-credit-on-delivery)
  - [What phase 1 code goes away](#what-phase-1-code-goes-away)
- [The client](#the-client)
- [Head-of-line blocking and deadlock](#head-of-line-blocking-and-deadlock)
- [Liveness](#liveness)
- [Layer rules](#layer-rules)
- [Configuration](#configuration)
- [Observability](#observability)
- [Memory budget](#memory-budget)
- [Performance](#performance)
- [Security and threat model](#security-and-threat-model)
- [Failure modes](#failure-modes)
- [Testing](#testing)
- [Build, CI and packaging](#build-ci-and-packaging)
- [Deployment changes](#deployment-changes)
- [Decisions that need an ADR](#decisions-that-need-an-adr)
- [Extension points](#extension-points)
- [Open questions](#open-questions)
- [Exit criteria](#exit-criteria)

## Purpose

At the end of phase 2:

- One `jburrow tcp` session carries up to 1,000 visitor connections at once. A visitor that stops
  reading slows only its own connection. The others keep their speed, and an SSH session stays
  interactive next to a stalled bulk transfer through the same tunnel.
- The protocol has flow control, specified byte by byte in `docs/protocol.md` as
  `jburrow.v1-draft.2`, with conformance vectors and a fuzzed model of the window rules that both
  sides share.
- The most memory a session can make the server hold is set by configuration and doesn't depend on
  the number of streams. A client that stops reading, or a visitor that stops reading, leaves the
  server's heap and direct memory flat.
- Two streams that depend on each other (an application that won't read one connection until
  another makes progress) can't deadlock a session, as they could if phase 1's model carried more
  than one stream.

## Goals and non-goals

**Goals**

- **Isolation between streams.** Backpressure on a stream reaches that stream's source and nothing
  else, in both directions.
- **A memory bound per session that doesn't depend on stream count.** Per-stream windows alone would
  let a session hold its stream limit times the stream window. A session window caps the total.
- **No buffering of unsent data.** Phase 1's relay never held bytes it hadn't sent. Phase 2 keeps
  that: a source is read only as far as it can be sent. The single exception is one byte per stream,
  forced by the epoll transport ([The read gate](#visitor-to-client-the-read-gate)).
- **The rules are pure Java that both sides share**, tested exhaustively and fuzzed, like phase 1's
  stream lifecycle and liveness. Netty only carries out the decisions.
- **Fail closed.** A peer that sends past a window broke the protocol, and the receiver says so at
  the first frame that does.
- **Fairness without a scheduler.** Streams that compete for a session's credit get it roughly in
  turn, through the event loop's own I/O order, with no priority queue to maintain.

**Non-goals**

- Window auto-tuning (growing a fast stream's window to match the path's bandwidth-delay product).
  The protocol allows it, since a receiver may always grant more credit than it was given back, but
  the reference implementation uses fixed windows until the load test shows they limit someone
  ([Open questions](#open-questions)).
- Stream priorities or weights. HTTP/2 dropped its priority scheme (RFC 9113) for being little used
  and hard to get right. Fair turns are enough for TCP tunnels.
- More than one tunnel per session (roadmap §1, v1), HTTP tunnels (phase 3), reconnect and `GOAWAY`
  (phase 4), accounts (phase 5).
- Per-IP limits on visitor connections (phase 6). Phase 2 adds a server-wide stream cap only
  ([Admitting streams](#admitting-streams)), and §Security and threat model says what that leaves
  open until then.

## Where this phase departs from PROJECT.md

`PROJECT.md` §Backpressure describes per-stream credit windows of 256 KiB and session-level
writability that pauses every public channel of a session. This design keeps both and changes three
things:

| Change | Why |
|---|---|
| A **session window** on top of the stream windows: `WINDOW_UPDATE` on stream 0 returns credit for the whole session, as in HTTP/2 and QUIC. | With stream windows alone, a session can make the server hold `maxConcurrentStreams × 256 KiB`, 250 MiB at 1,000 streams, from one client's data written slowly to its visitors. The session window caps it at 512 KiB. |
| **Each side announces its own receive windows** in its `HELLO`, instead of both using a fixed 256 KiB. | A window protects the receiver's memory, so the receiver chooses it, as HTTP/2's `SETTINGS_INITIAL_WINDOW_SIZE` does. A third-party client on a small device can announce smaller windows; an operator can tune the server's. |
| **A slow visitor or local service no longer stops the connection being read.** Phase 1 paused the whole session; phase 2 withholds credit for that stream only. Session writability stays, but only as a cap on how much each source may be read. | Pausing the connection is what causes head-of-line blocking and the deadlock between dependent streams ([Head-of-line blocking and deadlock](#head-of-line-blocking-and-deadlock)). |

Two smaller additions: a server-wide cap on concurrent streams, since raising the per-session limit
from 1 to 1,000 multiplies the visitor connections a server can be asked to hold; and the session
write buffer drops from `128KB-512KB` to `64KB-256KB` to keep the per-session budget near phase 1's
([Memory budget](#memory-budget)).

`PROJECT.md` §Backpressure, its build roadmap entry for phase 2 and its status line are updated in
the same change as this document.

## Runtime view

No new port, listener or host. What changes is how many visitor connections share one session, and
the credit that flows back against the data:

```mermaid
flowchart LR
    subgraph Visitors
        V1[Visitor 1<br/>bulk download, not reading]
        V2[Visitor 2<br/>SSH]
        V3[Visitor ... 1000]
    end
    subgraph Pod["jburrow-server: one session, one event loop"]
        TL[Tunnel listener :20417]
        R[Stream relay<br/>read gate per visitor]
        F[Session flow<br/>stream windows + session window]
    end
    subgraph Laptop
        C[jburrow tcp 22<br/>local connector]
        S[sshd / local service]
    end
    V1 & V2 & V3 --> TL --> R
    R <-->|DATA, OPEN, CLOSE| C
    C -.->|WINDOW_UPDATE stream n / stream 0| F
    F -.->|WINDOW_UPDATE stream n / stream 0| C
    C <--> S
```

Visitor 1's stalled download fills its own stream window and stops there. Visitor 2's keystrokes
keep their own credit and get through.

## Protocol draft 2

[`docs/protocol.md`](../protocol.md) is updated to `jburrow.v1-draft.2` in this phase and is the
authority on the wire. This section is the design behind it: the rules the specification will hold
normatively, and the reasoning that won't go there. [ADR-0009](../adr/0009-flow-control.md)
records the decisions.

### Why a new draft

A peer of draft 1 sends `DATA` whenever it has bytes. A draft 2 receiver treats `DATA` past a
window as a session error, so the two can't talk to each other, and the change has to be a new
subprotocol token rather than a capability (ADR-0007: anything new that isn't an optional JSON field
needs a capability or a new version, and before v1.0.0 incompatible changes bump the draft). Before
v1.0.0 a server speaks exactly one draft, so the phase 2 server refuses draft 1 clients with
`UNSUPPORTED_PROTOCOL_VERSION`, and the CLI tells the user to upgrade. Testers get the new CLI before
the server is deployed ([Deployment changes](#deployment-changes)).

### Announced windows

Both `HELLO`s gain a required `windows` object: **this side's receive windows**, which is the credit
its peer starts with.

| Field | Type | Rule |
|---|---|---|
| `windows` | object | The sender's receive windows. |
| `windows.stream` | integer (32-bit) | 65,536 to 2^31 − 1. The window each new stream starts with, for `DATA` towards the sender of this `HELLO`. |
| `windows.session` | integer (32-bit) | At least `windows.stream`, at most 2^31 − 1. The session window, for `DATA` towards the sender of this `HELLO` on all streams together. |

```json
{
  "sessionId": "0b8e5c1e-7c55-4c46-9a3e-2f1d0c9e4b7a",
  "server": { "name": "jburrow-server", "version": "0.3.0" },
  "capabilities": [],
  "limits": { "maxFramePayload": 65536, "maxTunnels": 1, "maxConcurrentStreams": 1000,
              "pingIntervalMs": 15000, "idleTimeoutMs": 45000 },
  "windows": { "stream": 262144, "session": 524288 }
}
```

- **Why a minimum of 64 KiB.** A one-byte window is legal in HTTP/2, and it makes the peer send
  one-byte frames, each with a 10-byte header, a WebSocket header and a credit round trip. A window
  at least as large as the largest `DATA` payload means a sender with credit can always fill a
  frame, and a peer can't force the other side into byte-at-a-time work by announcing a tiny window.
- **Why the session window must be at least the stream window.** A smaller one would make the
  stream window meaningless. Requiring it catches a misconfiguration at the `HELLO` instead of
  leaving it to show up as poor throughput.
- **Fixed for the session.** HTTP/2 lets `SETTINGS` change the initial window mid-connection, and
  the result (windows that go negative, retroactive adjustment of every open stream) is one of the
  most error-prone parts of RFC 9113. Here the initial windows never change. A receiver that wants
  a larger window grants extra credit with `WINDOW_UPDATE`, which needs no special rule.
- The pipelined handshake still works. The client sends its `HELLO` (with its windows) first, and no
  `DATA` flows in either direction until the session is established, so each side knows the other's
  windows before it may send.

### Sending

A sender keeps two credits towards its peer: one per stream, starting at the peer's
`windows.stream`, and one for the session, starting at the peer's `windows.session`.

- A `DATA` frame of `n` bytes may be sent only when `n` is at most the stream's credit **and** at most
  the session's credit. Sending it takes `n` from both.
- Only `DATA` is flow-controlled. `OPEN`, `CLOSE`, `WINDOW_UPDATE` and the stream 0 frames are not,
  so a sender with no credit can still close or reset a stream, answer a `PING` and return credit.
- A sender may always send less than its credit allows. Credit is permission, not obligation.
- **`OPEN` and optimistic data.** The server may still send a visitor's first bytes right behind
  `OPEN` (protocol.md §9.4), now up to the client's stream window. Phase 1's client had to stop
  reading the connection when it held too much for a stream it was still connecting. The window
  makes that unnecessary: the client holds at most its own `windows.stream` per connecting stream,
  by construction.

### Receiving

A receiver keeps, per stream and for the session, how much its peer may still send: its window minus
what has arrived, plus what it has given back.

- `DATA` that arrives counts against the session window first, then against its stream's window.
- `DATA` for a stream that is **closed** (in flight when it closed, protocol.md §9.3) still counts
  against the session window. The receiver discards it and counts it as released at once, so its
  session credit comes back ([Returning credit](#returning-credit)). Without this rule, every reset
  would leak session credit, and after enough resets the session would stall for good. HTTP/2 has
  the same rule for the same reason.
- `DATA` that ends in a stream error (protocol.md §9.2: `DATA` after the peer's own `CLOSE`) also
  counts against the session window and is released at once.

### Returning credit

A receiver gives credit back with `WINDOW_UPDATE`: on a data stream for that stream's window, on
stream 0 for the session window.

- **Credit is returned for released bytes.** A byte is released when the receiver no longer holds
  it: written to its destination socket, or discarded. Credit given back when bytes merely arrive
  would bound nothing. Credit given back when they are released makes each window the most a
  receiver can be made to hold.
- **When.** A receiver MUST return session credit once the session's unreturned released bytes reach
  half its session window, and stream credit once a stream's reach half its stream window. It MAY
  return credit sooner. Half the window is the standard trade-off (Netty's HTTP/2 flow controller and
  yamux both use it): updates stay rare, one for every 128 KiB on a stream, and the sender never runs
  dry while the receiver is draining.
- **No deadlock.** With that rule a sender's credit never stays at zero while the receiver holds
  nothing: when a receiver has released everything and returned nothing, it is holding back less
  than half the window, so the sender still has more than half. [The flow control
  model](#the-flow-control-model)'s fuzz target proves it for any interleaving.
- **More than released.** A receiver MAY grant more credit than it has released, to let a fast
  stream use a bigger window. That is the hook for auto-tuning later. It may never take credit back.
- **Not for a stream the peer has finished.** Once a receiver has received the peer's `CLOSE` on a
  stream, no more `DATA` comes on it, and the receiver sends no further `WINDOW_UPDATE` for that
  stream. Session credit for its released bytes still goes back on stream 0.

### Violations

Every violation of a window is a session error with a new code, **`FLOW_CONTROL_ERROR`** (session
scope, close code 1002):

| Received | Error |
|---|---|
| `DATA` longer than the session's remaining window | `FLOW_CONTROL_ERROR` |
| `DATA` longer than its stream's remaining window | `FLOW_CONTROL_ERROR` |
| `WINDOW_UPDATE` that takes a credit past 2^31 − 1 | `FLOW_CONTROL_ERROR` |

HTTP/2 makes a stream-window overrun a stream error. This protocol makes it a session error, because
no timing can make a correct sender overrun a window. A receiver's view of its peer's credit only
grows when it sends an update, and the sender's view only grows when the update arrives, so the
sender's view is never larger. An overrun is always a bug, and the protocol's rule for bugs is to
fail at the first bad frame (ADR-0007).

### Windows and stream states

The stream state table (protocol.md §9.2) gains two columns. `WINDOW_UPDATE` on a data stream:

| State | `WINDOW_UPDATE` received | `WINDOW_UPDATE` sent |
|---|---|---|
| Open | credit added | allowed |
| HalfClosedLocal | accepted, credit ignored (this side sends no more `DATA`) | allowed |
| HalfClosedRemote | credit added | MUST NOT (the peer sends no more `DATA`) |
| Closed | discarded | MUST NOT |

A `WINDOW_UPDATE` for a stream id that was never opened is `PROTOCOL_ERROR`, like every other frame
(§9.3). On stream 0 it is allowed in any state once the session is established. Before that no
`DATA` has flowed, so there is no credit to return, and it is `PROTOCOL_ERROR` under the frame order
rules (§8.2).

### What else changes in the specification

| Section | Change |
|---|---|
| §1 change log | The draft 2 row. |
| §4.1 | `jburrow.v1-draft.2` is this document. |
| §6 registry, §6.6 | `WINDOW_UPDATE` is sent by both sides on stream 0 or a data stream, and is defined (no longer "layout only"). A new example for stream 0. |
| §7.3, §7.4 | `windows` in both `HELLO`s, with examples regenerated by the codec. |
| §8.2 | `WINDOW_UPDATE` allowed in both directions once established; still `PROTOCOL_ERROR` before. |
| §8.5 | The limits paragraph mentions that windows bound the receiver's memory. |
| §9.2 | The two new columns above. |
| §9.4 | Optimistic data bounded by the client's stream window; the "stop reading when holding too much" text goes. |
| new §10 Flow control | Windows, sending, receiving, returning credit, violations: the rules above. Liveness becomes §11, Errors §12, Receiver rules §13, Limits §14, Conformance §15, Security considerations §16, Reference implementation §17. Every `§10`–`§16` reference in code and docs moves with them. |
| Liveness | Notes that a side using windows has no stream-level reason to stop reading the connection. The pausing rules stay, for peers that pause anyway. |
| Errors | `FLOW_CONTROL_ERROR`: session, close code 1002. |
| Receiver rules | Step 5: `WINDOW_UPDATE` may be on any stream. Step 9: the window checks. |
| Limits | Rows for the two windows and their bounds. |
| Security considerations | Windows bound what a peer can make a receiver hold; the 64 KiB minimum stops a peer forcing tiny frames. |

`FrameType.StreamKind` gains `ANY` for `WINDOW_UPDATE`, and `Frame.WindowUpdate` accepts stream 0.
The conformance vectors gain `WINDOW_UPDATE` on stream 0 as valid for both roles, and the draft 1
`HELLO` vectors are regenerated with `windows`.

## The flow control model

PROJECT.md: "The window accounting is pure Java in the domain layer, so it can be unit-tested
exhaustively." Both sides need exactly the same accounting, so, like `StreamLifecycle` and
`Liveness` in phase 1, it lives in the protocol model, in a new package `io.jburrow.protocol.flow`,
and each side's domain holds it.

```java
/** What this side may still send towards its peer, on one stream or the session. */
public final class SendCredit {
    public long available();
    void spend(int bytes);              // more than available: IllegalStateException, a bug in the caller
    void grant(int increment);          // from WINDOW_UPDATE; past 2^31 - 1: ProtocolException FLOW_CONTROL_ERROR
}

/** What the peer may still send towards this side, and the credit this side owes it. */
public final class ReceiveWindow {
    public int size();
    public int remaining();             // the peer's credit, in this side's view
    public int held();                  // received and not yet released
    public int unreturned();            // released, credit not yet sent back
    void receive(int bytes);            // more than remaining: ProtocolException FLOW_CONTROL_ERROR
    int release(int bytes);             // written or discarded; returns the increment to send now, 0 if none
    void stopReturning();               // the peer finished sending: no further increments
}

/** One stream's two directions. */
public final class StreamFlow {
    public SendCredit send();
    public ReceiveWindow receive();
}

/** One session's accounting: its own windows, the peer's, and the session-level credit both ways. */
public final class SessionFlow {
    public SessionFlow(int localStream, int localSession, int remoteStream, int remoteSession);
    public StreamFlow openStream();
    public SendCredit sessionSend();
    public ReceiveWindow sessionReceive();
    public int sendable(StreamFlow stream);                      // min of stream and session credit
    public void onSent(StreamFlow stream, int bytes);
    public void onSessionUpdate(int increment);                  // WINDOW_UPDATE on stream 0
    public void onStreamUpdate(StreamFlow stream, int increment);
    public void onReceived(StreamFlow stream, int bytes);        // session window checked first
    public Credit onDiscarded(int bytes);                        // closed or reset stream: received and released at once
    public Credit onReleased(StreamFlow stream, int bytes);      // stream null: its entry is gone; session credit only
    public void onPeerFinished(StreamFlow stream);               // the peer's CLOSE arrived
}

/** The WINDOW_UPDATEs to send: a session increment, a stream increment, either or both zero. */
public record Credit(int session, int stream) {}
```

- **Every call is a decision, every return is what to send.** The model never sends, schedules or
  reads a clock. That keeps it testable without sockets and identical on both sides.
- **`SessionFlow` is the only way in.** `SendCredit` and `ReceiveWindow` change only through
  package-private methods, so outside `io.jburrow.protocol.flow` they are read-only views: an adapter
  can ask how much it may send, and can't do its own arithmetic on credit. A call that throws changes
  nothing.
- **Release is checked.** `release` of more than `held()` throws `IllegalStateException`. The
  adapters release each byte exactly once, on write completion (success or failure) or on discard,
  and a double release is a bug the tests must catch, because it would hand the peer credit for
  memory still in use.
- **`Windows`** is the control record (`io.jburrow.protocol.control.Windows`) that both `HELLO`
  records carry, validated in its constructor like every control record. It arrives with the wire
  changes, after the model, so `SessionFlow` takes the four sizes as integers.
- **The fuzz target** (`SessionFlowFuzzTest`) simulates two peers with a queue of frames in flight
  each way, any number of streams, and an adversarial order of sends, deliveries, releases, resets
  and closes. It checks four invariants at every step: no receiver ever holds more than its windows;
  a sender following the model never triggers `FLOW_CONTROL_ERROR`; credit is conserved (sender's
  credit + data in flight + held + unreturned + updates in flight = window, per stream and per
  session); and when the receiver releases everything it holds, every sender still sending has more
  than half of each window back (no deadlock, and credit returned at half the window).

`StreamLifecycle` gains the two `WINDOW_UPDATE` events of the table above, and stays the authority
on stream state. Flow accounting sits next to it in each stream's entry, not inside it, so the state
machine's tests and fuzz target stay as they are.

## Choosing the windows

| Window | Server receives (announced by the server) | Client receives (announced by the client) |
|---|---|---|
| Stream | 256 KiB | 256 KiB |
| Session | 512 KiB | 1 MiB |

**Stream, 256 KiB** is `PROJECT.md`'s figure and yamux's default. With credit returned at half, a
stream sends at most a window per round trip, so a stream's throughput is at most its window divided
by the round-trip time:

| Client ↔ server round trip | One stream (256 KiB) | Server-bound session (1 MiB) | Client-bound session (512 KiB) |
|---|---|---|---|
| 20 ms | 12.5 MiB/s | 50 MiB/s | 25 MiB/s |
| 50 ms | 5 MiB/s | 20 MiB/s | 10 MiB/s |
| 100 ms | 2.5 MiB/s | 10 MiB/s | 5 MiB/s |
| 200 ms | 1.25 MiB/s | 5 MiB/s | 2.5 MiB/s |

"Server-bound" is visitor-to-client traffic, limited by the client's windows; "client-bound" is the
other way. Phase 1's exit test measured 0.55 MiB/s through the tunnel, set by the laptop's uplink,
which every row above exceeds. A tester with a fast uplink and a long round trip will be limited by
these windows, which is what auto-tuning would fix later ([Open questions](#open-questions)).

**Server session, 512 KiB.** This is the number that bounds the server's memory. It is two stream
windows, so two visitors draining at full speed don't hold each other back, and it keeps the
per-session budget at phase 1's level ([Memory budget](#memory-budget)). A larger session window
would let more stalled visitors sit in it before the rest of the session feels it (see
[Head-of-line blocking and deadlock](#head-of-line-blocking-and-deadlock)), at 512 KiB of direct
memory per session per doubling. The trade is between memory and how many stalled visitors a session
absorbs; it isn't a throughput question at the uplink speeds above.

**Client session, 1 MiB.** The client is a developer's machine with memory to spare and one session.
A larger window lets visitor uploads to the local service run faster on long round trips, and the
server's memory doesn't depend on it.

## Module and package layout

Nothing new at module level. New and changed packages, as phase 1's layout with the changes marked:

```
jburrow-protocol/                      io.jburrow.protocol
├── wire/        ErrorCode (+ FLOW_CONTROL_ERROR), FrameType (StreamKind.ANY for WINDOW_UPDATE),
│                ProtocolVersion (CURRENT = jburrow.v1-draft.2)
├── control/     Hello, ServerHello (+ windows), Windows (new)
├── stream/      StreamEvent (+ WINDOW_UPDATE_RECEIVED, WINDOW_UPDATE_SENT), StreamLifecycle
├── flow/        new: SendCredit, ReceiveWindow, StreamFlow, SessionFlow, Credit
└── adapter/netty/  Frame.WindowUpdate (stream 0 allowed), FrameCodec (step 5),
                    CreditBoundedRecvByteBufAllocator (new)

jburrow-server-core/                   io.jburrow.server.core
├── tunnel/domain/       Session (+ SessionFlow, window events), StreamTable (entries carry
│                        StreamFlow), StreamAdmission (+ SERVER_STREAM_LIMIT)
├── tunnel/application/  StreamQuota (new): the server-wide stream cap
└── tunnel/adapter/netty/
    ├── control/  ControlSessionHandler (WINDOW_UPDATE dispatch), SessionStreams (+ onWindowUpdate),
    │             ControlSessionInitializer (announced windows and limits from settings),
    │             LivenessHandler (pause and resume removed)
    ├── edge/     EdgeSettings (+ windows, stream caps)
    └── tcp/      StreamRelay (read gate, credit on delivery), VisitorHandler (carry slot),
                  Backpressure (removed; its job moves into the read gate)

jburrow-client-core/                   io.jburrow.client
├── ClientSettings (+ windows)
└── tunnel/
    ├── domain/        ClientSession (+ SessionFlow, window events), PendingData (removed)
    └── adapter/netty/ LocalConnector (read gate, credit on delivery), LocalRelayHandler (carry
                       slot, no pending limit), ClientLivenessHandler (pause and resume removed)

jburrow-server/                        io.jburrow.server
├── config/        JBurrowProperties (+ stream window, session window, max concurrent streams,
│                  max streams), EdgeConfiguration (budget check at startup)
└── observability/ MicrometerTunnelMetrics (+ flow blocked counter, new refusal reason)

jburrow-cli/       ExitCodes (FLOW_CONTROL_ERROR → 6)
scripts/load/      StreamLoad.java (new): the many-streams load driver, run by hand
```

**Why the credit-bounded allocator is in `jburrow-protocol`.** Both sides read a source only as far
as its credit allows, with the same Netty mechanism and the same epoll subtlety
([The read gate](#visitor-to-client-the-read-gate)). `jburrow-protocol`'s `adapter.netty` is
already the Netty code both sides share (the codec), and ADR-0002 gives server and client no other
shared module. The allocator is one class that knows nothing about sessions: it caps a read at what
an `IntSupplier` says may be sent.

## Concurrency model

ADR-0008 is unchanged and phase 2 depends on it more than phase 1 did. A session's flow state (its
`SessionFlow`, every `StreamFlow`, the read gate of every visitor) is confined to the session's
event loop, together with every visitor channel. So:

- A credit check and the read it allows happen on one thread, with no gap in which another stream
  could spend the same credit.
- Returning credit is a write-completion listener on a visitor channel, which Netty runs on that
  channel's event loop: the session's loop. It touches the session's flow state directly.
- Resuming a hundred visitors when session credit arrives is a hundred `setAutoRead(true)` calls in
  a loop, with no locks.

**One new piece of global state**: `StreamQuota`, the server-wide count of open streams, an
`AtomicInteger` taken when a visitor is admitted and given back when its stream ends. ADR-0008
allows global state that has to be global, behind its own small synchronisation. The relay's byte
path never touches it: only admission and stream end do.

## The server

### Admitting streams

`VisitorHandler.channelActive` asks, in order:

1. Is the session established? Otherwise `NOT_ESTABLISHED`, as in phase 1.
2. Is the session below `maxConcurrentStreams` (1,000)? Otherwise `STREAM_LIMIT`.
3. Does `StreamQuota` have room (`jburrow.edge.max-streams`, 20,000)? Otherwise
   `SERVER_STREAM_LIMIT`.

A refused visitor is closed at once, unannounced, as in phase 1 (protocol.md §8.5). The quota is
taken last so a refusal at step 2 doesn't touch shared state, and it is released by the stream's end
on every path, which the tests check by counting the quota back to zero after every scenario.

Why a server-wide cap: phase 1 could hold at most 200 visitor connections (200 sessions × 1 stream).
Phase 2's per-session limit allows 200,000. Each costs a file descriptor, a few KiB of heap for the
channel and its pipeline, and kernel socket memory that cgroup v2 charges to the pod. 20,000 is
roughly 60 MiB of heap for channels in the worst case and far below the container's open-file limit,
and the load test measures it.

### Visitor to client: the read gate

Phase 1 read a visitor whenever the session channel was writable. Phase 2 reads a visitor only as far
as it can send right now. For each visitor:

```
sendable = min(stream credit, session credit, session channel headroom, 65,536)
```

where headroom is `Channel.bytesBeforeUnwritable()`: how much more the session channel's outbound
buffer takes before it crosses its high watermark. Session writability, phase 1's mechanism, becomes
one term of the same minimum.

- **A visitor is read only while `sendable > 0`.** When its own read takes `sendable` to 0, its
  `autoRead` goes off at once; when a `WINDOW_UPDATE` or a writability change raises it again,
  `autoRead` goes back on. When `sendable` falls to 0 for every visitor at once (session credit or
  headroom ran out), visitors are paused lazily: each one the next time it is actually read
  ([The one-byte carry](#visitor-to-client-the-read-gate), below), so an idle visitor is never touched.
- **Reads are capped at `sendable`.** `CreditBoundedRecvByteBufAllocator` wraps phase 1's
  `AdaptiveRecvByteBufAllocator` (64 bytes to 64 KiB). Its handle's `guess()` is the adaptive guess
  capped at `sendable`, and it allocates the read buffer with that capacity, so a socket read never
  returns more than can be sent. It doesn't let reads that the cap cut short shrink the adaptive
  guess: those reads were short because of credit, not because the visitor sent little. A visitor's
  read becomes one `DATA` frame, sent at once, with nothing left over.
- **One read per visitor per loop turn** (`maxMessagesPerRead` 1). When many visitors compete for
  session credit, each gets one read of up to 64 KiB before any gets a second. That is the fairness
  guarantee. It costs nothing measurable at 64 KiB per read, and it replaces the scheduler HTTP/2
  implementations keep for the same purpose.
- **Who is waiting for what.** A visitor blocked by its own stream credit is resumed by its stream's
  `WINDOW_UPDATE`. A visitor blocked by session credit or headroom is kept in the relay's
  `blockedOnSession` set and resumed, in the order it was blocked, when stream 0's `WINDOW_UPDATE`
  arrives or the session channel becomes writable again. Neither event scans visitors that aren't
  waiting.

**The one-byte carry.** A visitor can be read when its `sendable` is 0, in two ways. One is
deliberate: when session credit or headroom runs out, pausing every visitor of the session at once
would cost a system call per visitor (on epoll, `setAutoRead(false)` is an `epoll_ctl`), most of them
idle. So the relay pauses none of them in advance. The other is forced: when session credit runs out
partway through an event-loop turn, visitors that epoll already reported readable in that turn are
read anyway, because Netty checks `autoRead` before asking epoll, not before each ready channel it
was handed. Either way the read can't be zero bytes: Netty's native read returns 0 for a buffer of
capacity 0, and on epoll 0 means end of input, which would half-close the visitor. So the allocator
never guesses below 1. A visitor read with `sendable` at 0 gives up at most one byte, which is held
in its stream's carry slot; the visitor is paused and joins `blockedOnSession`; and the byte goes out
first, as a one-byte `DATA`, when credit returns. Nothing else is ever held unsent, and only visitors
that actually had data pay for a pause. NIO doesn't have the zero-byte problem, but both transports
follow the same rule so behaviour doesn't depend on the transport. An integration test drives both
paths with a minimal session window and many visitors sending at once, on both transports, and checks
the server never overruns a window ([Testing](#testing)).

A visitor's `FIN` still becomes `CLOSE`, once any carried byte has gone out ahead of it, since a
half-close must follow the stream's last byte. A reset discards the carried byte and needs no credit,
so a stream can always be reset at once.

### Client to visitor: credit on delivery

The client's `DATA` is checked and accounted by `Session.onDataReceived` (lifecycle first, then the
session and stream windows, raising `FLOW_CONTROL_ERROR` on an overrun). An accepted payload is
written to the visitor channel with a promise. The promise's listener calls `Session.onReleased` with
the payload's length when the write completes, successfully or not, and sends any `WINDOW_UPDATE` the
returned `Credit` names, with `writeAndFlush`: updates are rare enough (one per 128 KiB per stream,
one per 256 KiB per session) that coalescing their flushes would buy nothing.

- **A slow visitor holds back only itself.** Its writes don't complete, so its stream's credit isn't
  returned, so the client stops sending on that stream after 256 KiB. The session channel keeps being
  read for every other stream.
- **A closed stream gives its session credit back.** Writes queued to a visitor that is reset or
  closed fail, their listeners release the bytes, and the session credit returns. `DATA` that arrives
  for a closed stream is released as it is discarded.
- **Buffer ownership** is phase 1's (phase 1 §The data path). The listener releases credit, not
  buffers: Netty releases a written buffer itself.

### What phase 1 code goes away

| Phase 1 | Phase 2 |
|---|---|
| `Backpressure`: a visitor that can't take more stops the session channel being read and pauses liveness | Removed. The visitor's stream window does that job for its stream alone. Session writability moves into the read gate. |
| `jburrow.edge.visitor-write-buffer` | Removed. What a visitor channel holds is bounded by the stream window, so its watermarks no longer drive anything. |
| `LivenessHandler.pause` and `resume` | Removed, along with their callers. The server never stops reading a client for flow control any more. `Liveness`'s pausing rules stay in the protocol model, which the specification still allows. |
| Second visitor refused (`maxConcurrentStreams` 1) | 1,000 streams per session, 20,000 per server. |

## The client

The client is the server's mirror, with "visitor" read as "local connection", as in phase 1.

- **`ClientSession`** holds a `SessionFlow`, created when the server's `HELLO` arrives (it carries the
  server's windows). Each stream's entry carries its `StreamFlow` next to its lifecycle.
- **`HELLO`** carries `ClientSettings.windows()`, by default 256 KiB and 1 MiB. They aren't CLI flags:
  nobody running `jburrow tcp` needs to tune them, and an embedding application can set them through
  the builder.
- **Local reads** go through the same `CreditBoundedRecvByteBufAllocator` with the same `sendable`
  minimum (stream credit, session credit, control channel headroom, and `limits.maxFramePayload`
  instead of 65,536). Phase 1's loop that split a large read into frames goes, since no read is
  larger than one frame. The client is always on NIO, so the one-byte carry never triggers there; the
  code is shared and the rule is the same.
- **Server `DATA`** is written to the local connection with a promise, and credit goes back on
  completion. While the local connection is still being made, payloads wait in the stream's queue as
  in phase 1. The queue is bounded by the client's stream window, because the server can't send more
  until the client releases something, and nothing is released until the connection exists and the
  bytes are written. So **`PendingData` is removed**, and with it the rule that stopped reading the
  control connection when a connecting stream held too much. If the connection fails, the queued
  payloads are released, their session credit is returned, and the stream is reset with
  `LOCAL_SERVICE_UNREACHABLE`, as before.
- **A local service that stops reading** holds back its own stream's credit. The client keeps reading
  the control connection, keeps answering `PING`s, and every other stream keeps flowing.

## Head-of-line blocking and deadlock

| Scenario | Phase 1 model with many streams | Phase 2 |
|---|---|---|
| One visitor stops reading a large response | The server stops reading the client: every stream of the session stalls | That stream's credit runs out after 256 KiB; the others are unaffected |
| A local service stops reading one stream's upload | The client stops reading the server: every stream stalls | That stream's credit runs out; the others are unaffected |
| Two streams that depend on each other (a protocol that won't read connection A until it has written to connection B) | Deadlock: B's bytes queue behind A's paused connection | No deadlock: B has its own credit |
| A visitor sends faster than the client can take | Session writability pauses every visitor | The read gate pauses visitors as the session channel's headroom and session credit run out, and resumes them in turn as they return |
| Several visitors stall at once, together holding the server's whole session window (two at the defaults) | — | The client-to-visitor direction of that session stalls until one of them reads or goes. The other direction, and every other session, is unaffected |

The last row is the limit of any memory-bounded design. A receiver that holds at most `S` bytes per
session can be filled by `S / W` streams that each hold a full window `W` and never drain. Raising
`S` raises the memory bound; lowering `W` lowers per-stream throughput. An attacker who controls
enough visitor connections can always do this to one tunnel, as they can open its stream limit's
worth of connections and hold them. The defences are per-source limits on visitor connections, which
are phase 6. What phase 2 guarantees is that this attack can't reach another session or the server's
memory.

## Liveness

Liveness rules don't change. Their inputs do: in phase 1 a side stopped reading the connection when
one stream backed up, and the "paused reading pauses the clock" and "a paused side keeps pinging"
rules existed for that. In phase 2 neither side ever stops reading the connection for flow control.
Credit is withheld instead, so `PING`s and `PONG`s are always read promptly and the clock never needs
pausing.

The rules stay in the specification and in `Liveness`, because a third-party implementation may still
pause, and they still apply to it. The reference handlers' `pause` and `resume` methods go, since
nothing calls them. `SlowReaderIntegrationTest` (a stall twice the idle timeout in each direction)
stays, and now passes because the connection is never paused rather than because the clock is.

## Layer rules

No rule changes. `io.jburrow.protocol.flow` is protocol model: pure Java that `PROTOCOL_MODEL_IS_PURE`
already covers and that both cores' `domain` layers may depend on (ADR-0007).
`CreditBoundedRecvByteBufAllocator` is in `io.jburrow.protocol.adapter.netty`, where Netty is
allowed. `StreamQuota` is in `tunnel.application` and uses only `java.util.concurrent`.

## Configuration

| Key | Environment variable | `local` | `prod` | Notes |
|---|---|---|---|---|
| `jburrow.edge.stream-window` | | `256KB` | `256KB` | announced as `windows.stream`; 64 KiB to 2 GiB − 1 |
| `jburrow.edge.session-window` | | `512KB` | `512KB` | announced as `windows.session`; at least `stream-window` |
| `jburrow.edge.max-concurrent-streams` | | `1000` | `1000` | announced as `limits.maxConcurrentStreams`; was a constant, 1 |
| `jburrow.edge.max-streams` | | `20000` | `20000` | server-wide; at least `max-concurrent-streams` |
| `jburrow.edge.session-write-buffer` | | `64KB-256KB` | same | was `128KB-512KB` |
| `jburrow.edge.visitor-write-buffer` | | removed | removed | |

None needs a value in the production Secret. Validation is in `JBurrowProperties`, as in phase 1:
the window bounds, `session-window ≥ stream-window`, `max-streams ≥ max-concurrent-streams`.

**The budget check.** At startup `EdgeConfiguration` computes the per-session worst case from these
values ([Memory budget](#memory-budget)), multiplies it by `max-sessions`, adds the handshaking
connections, and compares the total with Netty's direct memory limit
(`PlatformDependent.maxDirectMemory()`). It logs the arithmetic at INFO, and at WARN if the total
exceeds 90% of the limit. It doesn't fail startup, because the worst case needs every session
saturated at once and an operator may knowingly overcommit, but nobody can configure past the budget
without being told.

## Observability

| Metric | Change |
|---|---|
| `jburrow_flow_blocked_total` | **new** counter, label `scope` (`stream`, `session`): a visitor paused because that credit ran out. A high `session` rate means clients are slower than their visitors; a high `stream` rate means visitors are slower than clients. |
| `jburrow_visitor_connections_refused_total` | new `reason` value `server_stream_limit` |
| `jburrow_protocol_errors_total` | new `code` value `FLOW_CONTROL_ERROR` |
| `jburrow_frames_total` | `WINDOW_UPDATE` now appears, by direction |
| `jburrow_backpressure_pauses_total` | `direction=inbound` only (session headroom); `outbound` is no longer emitted, since a slow visitor no longer pauses the session |

Still no metric is labelled with a session, stream, tunnel, port, address or principal.

**Logs.** The "stream closed" INFO line gains `blockedMs`: how long the stream spent with no credit
to send, which tells an operator whether a slow transfer was the visitor's fault or the client's. The
"session closed" line gains `maxStreams`, the most streams it had open at once.

## Memory budget

The pod limit, the JVM settings and the 256 MiB direct memory cap are phase 1's.

| Per established session, worst case | Bytes |
|---|---|
| Client `DATA` received and not yet written to visitors: the server's session window | 512 KiB |
| One message from a client that overruns its window, held until it is rejected | 64 KiB |
| Session channel outbound buffer: high watermark plus one frame | 256 KiB + 64 KiB |
| Carried bytes, at most one per stream | 1 KiB at 1,000 streams |
| **Total** | **≈ 0.88 MiB** |

200 sessions use at most about 175 MiB, and 64 handshaking connections about 4 MiB more: 179 MiB of
the 256 MiB cap, the same 70% as phase 1, now with up to 1,000 streams per session instead of 1.

Why the outbound buffer is bounded so tightly: a visitor read is capped at the session channel's
headroom, so the outbound buffer passes its high watermark by at most one frame, however many
visitors become readable at once. Phase 1's buffer could pass it by one frame per readable visitor
in the same event-loop turn, which was harmless with one visitor and wouldn't have been with a
thousand.

**Not in this table**: kernel socket buffers. Credit is returned when a write reaches a visitor's
socket, so what the kernel holds for a slow visitor is outside the window. Linux sizes a socket's
send buffer to its traffic, up to `net.ipv4.tcp_wmem`'s maximum (4 MiB by default), and cgroup v2
charges it to the pod. A visitor that reads fast enough to grow its buffer and then stops leaves that
buffer full. Phase 1 had the same exposure with one visitor per session; with 20,000 it matters. The
load test measures socket memory under many stalled visitors (`ss -tm`, the pod's memory), and
`SO_SNDBUF` on visitor channels is the lever if it has to be capped ([Open questions](#open-questions)).

**Heap.** Per stream the server keeps a channel, its pipeline, a `VisitorHandler`, a lifecycle and a
`StreamFlow`, a few KiB in all, and nothing that grows with the bytes carried. The slow-client test
measures heap after garbage collection at two points of a long transfer and requires it not to grow
between them.

## Performance

- **Per-byte cost.** Unchanged from phase 1 in the steady state: one read, one frame, no copy. Flow
  control adds two integer subtractions per frame and one write-completion listener per `DATA`
  written to a visitor or local connection.
- **Credit traffic.** One `WINDOW_UPDATE` (14 bytes on the wire plus WebSocket framing) per 128 KiB
  per stream, and one per 256 KiB on stream 0. Less than 0.02% of the data.
- **Pausing at scale.** Turning `autoRead` off and on is a system call per visitor on epoll. When a
  session's credit or headroom runs out, only the visitors that are actually read are paused (each
  after a one-byte read), and only the ones waiting are resumed; idle visitors cost nothing. In the
  worst case (1,000 active visitors on a session limited by its client's link) that is a few thousand
  `epoll_ctl` calls and one-byte frames per second, a small fraction of a core. The load test
  measures it.
- **Latency.** An interactive stream (SSH) next to bulk streams waits at most one turn of reads, each
  at most 64 KiB, before its next frame is read. The load test reports added p99 latency for a small
  echo stream alongside 499 bulk streams.
- **Opening many streams at once.** The client resolves each local target on a virtual thread
  (phase 1), so 500 simultaneous `OPEN`s resolve in parallel without blocking the event loop.

## Security and threat model

Phase 1's threats and mitigations stand. Phase 2 changes these:

| Threat | Mitigation | Proven by |
|---|---|---|
| A client sends more than the server can buffer, on one stream or many | Stream and session windows; overrun is `FLOW_CONTROL_ERROR` at the first frame | `FlowControlIntegrationTest`, `SessionFlowTest`, fuzzing |
| A client announces tiny windows to make the server do byte-at-a-time work | 64 KiB minimum window, enforced at `HELLO` (`VALIDATION_FAILED`) | `WindowsTest`, vectors |
| A client announces huge windows so the server queues unbounded data for it | The server's sending is capped by its own session channel headroom, whatever the client announces | `ReadGateIntegrationTest` |
| A client grants credit past 2^31 − 1 to wrap the server's counter | `FLOW_CONTROL_ERROR`; counters are `long` internally | `SendCreditTest` |
| Credit leaks through resets until a session stalls for ever | Discarded and failed writes release session credit; conservation fuzzed | `SessionFlowFuzzTest`, `ManyResetsIntegrationTest` |
| Visitors open connections until the server runs out of descriptors or memory | `max-concurrent-streams` per session, `max-streams` per server | `StreamAdmissionIntegrationTest` |
| Visitors to one tunnel stall to stop its other visitors | Contained to that session's client-to-visitor direction ([Head-of-line blocking and deadlock](#head-of-line-blocking-and-deadlock)); per-source limits are phase 6 | `HeadOfLineIntegrationTest` (contained, not prevented) |
| A slow visitor makes the client stop reading the server, so the session times out | The client never stops reading the control connection for flow control | `SlowReaderIntegrationTest` |

## Failure modes

| Scenario | Behaviour |
|---|---|
| A visitor stops reading a large download | Its stream stops after 256 KiB in flight. The client stops reading that stream's local connection. Everything else continues. |
| The client's network is slow | Session credit and the session channel's headroom run out; visitors are paused in turn and resumed as credit returns. Server memory stays at its budget. |
| The local service stops reading an upload | The client withholds that stream's credit; the server stops reading that visitor. |
| A buggy client sends past a window | `ERROR FLOW_CONTROL_ERROR`, the session closes, the CLI exits with code 6. |
| A phase 1 CLI connects to the phase 2 server | `UNSUPPORTED_PROTOCOL_VERSION`, exit code 6, "upgrade jburrow". |
| 1,001st visitor to a session | Closed at once, counted as `stream_limit`. |
| 20,001st visitor to the server | Closed at once, counted as `server_stream_limit`. |
| The session ends with many streams open | Each is reset with `CANCELLED`, as in phase 1; queued writes fail, their credit is released into a session that is gone, and nothing is sent. |
| Many visitors become readable as session credit runs out | Each one read with no credit gives up at most one byte, is paused, and waits its turn; idle visitors aren't touched. |

## Testing

| Test | Kind | Proves |
|---|---|---|
| `SendCreditTest`, `ReceiveWindowTest`, `SessionFlowTest` | unit | every rule above, including the half-window update threshold, release checks and overflow |
| `StreamLifecycleTest` | unit | the two new columns, cell by cell |
| `SessionFlowFuzzTest` | Jazzer | no overrun, no unbounded holding, credit conservation, no deadlock, for any interleaving of two peers |
| `WindowsTest`, control record and `ControlJsonTest` additions | unit | `windows` field rules; `HELLO` without `windows` is `VALIDATION_FAILED` |
| `ConformanceVectorsTest`, `ProtocolDocumentExamplesTest` | codec | draft 2 vectors and examples |
| `CreditBoundedRecvByteBufAllocatorTest` | unit | the cap, the one-byte floor, adaptive guesses not shrunk by capped reads |
| `SessionTest`, `StreamTableTest`, `StreamQuotaTest` | unit | window events through the server domain; the server-wide cap |
| `FlowControlIntegrationTest` | real edge, `ScriptedPeer` client | the server never sends past the client's windows; it resumes on `WINDOW_UPDATE` for the stream and for stream 0; it returns credit as it writes to visitors, at half the window; every violation is `FLOW_CONTROL_ERROR`; `WINDOW_UPDATE` before established is `PROTOCOL_ERROR`; credit returns for discarded and reset streams |
| `ReadGateIntegrationTest` | real edge, `ScriptedPeer`, many visitors | the one-byte carry under a forced race with a 64 KiB session window and 50 visitors, on epoll and NIO; outbound buffer never more than one frame over its high watermark; turns are fair (no visitor gets two reads while another waits) |
| `StreamAdmissionIntegrationTest` | real edge | per-session and server-wide caps, quota back to zero after every scenario |
| `ClientSessionIntegrationTest` additions | real client, `ScriptedPeer` server | the client mirror of the above; queued data before connect bounded by the window; control connection never paused |
| `ManyStreamsIntegrationTest` | full server + real client | 500 concurrent visitors, 1 MiB each way, checksums, one session |
| `HeadOfLineIntegrationTest` | full server + real client | a stalled 64 MiB download beside an echo stream: the echo stays under 100 ms per round trip; then the stalled visitor reads and finishes intact |
| `DependentStreamsIntegrationTest` | full server + real client | two streams whose local service won't read A until it has written to B: completes |
| `SlowClientIntegrationTest` | full server + real client | phase 2's exit test: 500 streams with the client's control connection throttled; direct bytes held stay under the per-session budget, and heap after GC doesn't grow between the first and last quarter of the transfer |
| `ManyResetsIntegrationTest` | full server + real client | 10,000 streams opened and reset mid-transfer: the session's credit is whole at the end |
| `SlowReaderIntegrationTest`, `TunnelEndToEndIntegrationTest`, `HeartbeatIntegrationTest` | existing | still green on draft 2 |
| `TunnelMetricsIntegrationTest`, `LogHygieneIntegrationTest` | existing, extended | the new metric and fields |
| `ErrorCodeSpaceTest` | existing | `FLOW_CONTROL_ERROR` doesn't collide with a REST code |

`ScriptedPeer` gains windows: it announces them in its `HELLO`, tracks the other side's credit, and
fails the test the moment the implementation under test sends past it. A conformance test is only as
good as its peer's strictness.

**Load test.** `scripts/load/StreamLoad.java` is a single-file Java program (`java StreamLoad.java`,
no build, no dependencies) that opens N concurrent connections to a tunnel, sends a set number of
bytes on each, reads them back from an echo service, checks each checksum, and reports connect and
completion latency percentiles and total throughput. It runs by hand, against a local server and
against production, and is the instrument for the exit criteria and the open questions.

## Build, CI and packaging

- No new dependency. Jazzer gains the `SessionFlowFuzzTest` target in the `fuzz` job.
- `reflect-config.json` gains `Windows`; `NativeImageMetadataTest` already requires it.
- `scripts/native-smoke.sh` opens 50 concurrent streams through the native binary instead of one, so
  the native image's multiplexing is proven on every pull request.
- The CLI's version line shows `protocol jburrow.v1-draft.2`.

## Deployment changes

No new resource, port, Secret key or DNS record. What a deploy has to get right:

- **Order.** The new server refuses every phase 1 CLI. Build the native binary from the phase 2 commit
  and give it to the testers before the server is deployed, and tell them that the old one stops
  working at the deploy.
- **Settings.** All new keys have defaults. The startup budget line in the log confirms the arithmetic
  on the production pod.
- **Watch.** `jburrow_netty_direct_memory_bytes`, `jburrow_flow_blocked_total` and the pod's memory
  during the exit test, as the evidence for [Memory budget](#memory-budget).

The deployment runbook's phase 2 section has the steps.

## Decisions that need an ADR

| ADR | Decision | Written in |
|---|---|---|
| 0009 | Flow control: credit windows per stream and per session, as in HTTP/2 and QUIC; each side announces its own receive windows, fixed for the session; credit returned for released bytes, at half the window; every overrun a session error; sources read only as far as they can be sent, paused lazily with a one-byte carry; no read pausing of the control connection for flow control; protocol draft 2 | the flow control model checkpoint |

## Extension points

| Phase | Adds | Where |
|---|---|---|
| 3 | HTTP tunnels | Each HTTP request and response is a stream and flows under the same windows; `HEADERS` is a control payload and isn't flow-controlled, so a response head always gets through |
| 4 | `GOAWAY` and drain | Streams finishing during a drain keep their credit; draining needs no flow-control change |
| later | Window auto-tuning | A receiver policy in `SessionFlow`: grant extra credit to a stream whose updates keep finding its sender out of credit while its destination drains. No wire change |
| later | Raw TCP+TLS transport | Unchanged: windows are per session, not per transport |
| 6 | Per-source visitor limits | The visitor's address is already in `VisitorHandler`; a per-source count sits next to `StreamQuota` |

## Open questions

- **Auto-tuning.** At 200 ms, one stream tops out at 1.25 MiB/s. If testers with fast uplinks hit it,
  the extension above lifts it. Decided by the load test against a real long round trip.
- **Kernel socket memory.** Whether visitor send buffers need `SO_SNDBUF`, and at what size, depends on
  what the load test sees with thousands of stalled visitors.
- **Heap and direct memory split.** The pod's JVM settings give the heap 75% of 1 GiB and direct
  memory 256 MiB. Once the load test has measured heap use with 1,000 streams per session, the split
  may be worth rebalancing.
- **A larger server session window.** 512 KiB lets two stalled visitors fill a session's
  client-to-visitor direction. If phase 6's per-source limits come later than expected, 1 MiB (with
  `max-sessions` lowered to keep the budget) is the alternative.

## Exit criteria

Phase 2 is done when all of these hold:

- [ ] On production, from a network other than the laptop's, `StreamLoad` opens **500 concurrent
      connections** to one native `jburrow tcp` session forwarding to a local echo service, sends
      1 MiB on each, and gets every byte back with matching checksums. The server logs one session
      with 500 streams served.
- [ ] `SlowClientIntegrationTest` is green: with 500 streams and a throttled client, the direct memory
      the server holds stays within the per-session budget, and heap after GC doesn't grow.
- [ ] On production, with one visitor stalled on a large download through the tunnel, an SSH session
      through the same tunnel stays interactive.
- [ ] A 100 MiB transfer through the phase 2 tunnel, from the same laptop and network as phase 1's
      exit test, is no slower than phase 1's measured 0.55 MiB/s.
- [ ] During the 500-stream test, `jburrow_netty_direct_memory_bytes` stays below the budget the
      startup log printed.
- [ ] The `verify`, `native` and `fuzz` jobs are green on `main`.
- [ ] `docs/protocol.md` is `jburrow.v1-draft.2` with its vectors, and ADR-0009 is written and
      indexed.
- [ ] `git grep` across the repository finds no hostname, IP address, organization or repository
      name that belongs to the deployment.
