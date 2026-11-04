# 14. Reconnect and resume as the `resume` capability of draft 2

- Status: accepted
- Date: 2026-11-03

## Context

Until phase 4 a session lives exactly as long as one WebSocket connection, and whatever ends the
connection ends the tunnel: a deploy, an ingress-nginx reload anywhere on the cluster, a laptop
changing networks. The CLI exits with code 4, and the user runs it again hoping nobody took the URL
in the meantime. Phase 4 makes the tunnel outlive the connection
(`docs/phase-4-staying-connected/ARCHITECTURE.md`).

There are three gaps to cross, and they need different things:

- **The server saw the connection drop** (a reload, a client crash). The session has ended; its names
  and ports have to stay with their owner long enough for the client to come back.
- **The server didn't see it** (the laptop moved networks; the old connection is silent but open).
  The old session is still active until its idle timeout, 45 s, and it still holds the names, ports
  and one of its owner's three sessions.
- **The server process was replaced** (a deploy). Nothing in memory survives.

Phase 2 and 3 CLIs are in testers' hands, and ADR-0007 says a version grows through capabilities
negotiated in `HELLO`; ADR-0011 did exactly this for `http`. `GOAWAY` (`0x0A`) has been an assigned
but reserved type since draft 1.

## Decision

- **A capability, not a draft.** Resume is `resume`, draft 2's second capability. It introduces the
  payload of `GOAWAY`, the optional fields `resume` in `REGISTER` and `resumeToken` in its reply, the
  session code `SESSION_REPLACED` (close code 1000), and a Draining state. In a session without it,
  the two fields are unknown keys and ignored whatever their values (§4.3), `SESSION_REPLACED` is an
  unknown code, and `GOAWAY` is refused at step 9 as it always was. A client with a token to present
  sends its `REGISTER` once the server's `HELLO` has said whether it has `resume`.
- **`GOAWAY` warns before a drain.** Once per session, only once established, server to client:
  `reconnectAfterMs` (0 to 60 s, drawn per session from a configured spread) and `drainMs` (0 to
  300 s). After it the server opens no stream, the streams already open finish, and the session ends
  with `ERROR SERVER_SHUTTING_DOWN`. The client never closes first. A second `GOAWAY`, one before the
  `REGISTER` reply, and an `OPEN` after one are `PROTOCOL_ERROR`.
- **A resume token names a live session to end, nothing more.** `jbr_` and 32 random bytes in
  base64url, issued in every `REGISTER` reply of a session with `resume`. A later `REGISTER` that
  presents it ends that session with `SESSION_REPLACED` before the session limit is checked
  (registration step 2a), but only if the session is still open on this server process and belongs to
  the same principal as the new `REGISTER`'s authtoken; otherwise the step does nothing. The token is
  never a credential: the authtoken is checked as before. The server keeps only its SHA-256, in
  memory, for the session's lifetime; the client keeps it in memory for its next session. Neither
  side logs it, prints it or writes it anywhere.
- **Holds carry names and ports across the gap.** After any session ends, its names and ports are
  held for their principal for one resume window (120 s); only that principal may take them back.
  Across a server restart the holds are carried by durable leases (ADR-0015), not by tokens.
- **The client asks again for what it had.** Every reconnect sends a `REGISTER` with the names and
  ports the previous session got, and its token. If one the server chose is refused, the client asks
  once more without it and reports a new URL. If one the user chose is refused, the client stops.

## Consequences

- Phase 2 and 3 CLIs keep working through the deploy that brings this in, and get
  `ERROR SERVER_SHUTTING_DOWN` at a deploy as before. Holds still return their names if they run the
  command again within the window.
- A silent session no longer blocks its owner's names and session slot for 45 s: the reconnect ends
  it at once.
- A stolen token is worth nothing without the authtoken, and a leaked authtoken was already the whole
  account. A token is good once, and only while its session is open.
- Nothing about resume touches Postgres. Token lookup is an in-memory index of live sessions; across
  a restart the token means nothing, and the holds seeded from leases do the work.
- The capability is defined on the wire before either side uses it: the server lists `resume` in its
  `HELLO` only once it drains, and the client offers it only once it reconnects, so each half can ship
  separately without changing what production does.
- Registration gains a step that waits, on the registration's virtual thread, for another session's
  release; it is bounded, and a port still bound afterwards is refused as `PORT_UNAVAILABLE`, which
  the client retries.
- Open streams don't survive a gap. A visitor's TCP connection belongs to the process that accepted
  it, and moving streams between processes is not planned.

## Alternatives considered

- **A new draft, `jburrow.v1-draft.3`.** It would refuse every phase 2 and phase 3 CLI at the upgrade
  for a change that is purely additive, at exactly the deploy meant to stop deploys from breaking
  tunnels.
- **Storing tokens so they work across a restart.** It would put a secret in the database and buy
  nothing a hold doesn't already give: after a restart there is no old session to end, and the names
  are already held for their owner. What only a token can do is end a session the server still
  thinks is alive, which only matters on the same process.
- **Taking over active claims by principal alone**, without a token. Two terminals running with the
  same authtoken and `--subdomain` would steal each other's URL back and forth on every reconnect. An
  active claim yields only to the token of its own session.
- **The client falling back to a random name for a name the user chose.** The user asked for that
  URL; a different one would be a surprise, possibly shared with someone else already. The fallback
  is only for names the server chose, which nobody but the client depends on yet.
- **The client closing as soon as `GOAWAY` arrives.** It would cut its own open streams for nothing:
  there is nowhere to reconnect to until the new server is up, so the drain is free time for them.
- **A token that is also a credential**, letting a reconnect skip the authtoken check. It would make
  the token as valuable as the authtoken while living in more places, and would let a revoked
  authtoken (phase 5) keep a session alive through reconnects.
