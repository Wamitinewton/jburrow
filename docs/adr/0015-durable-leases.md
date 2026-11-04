# 15. Durable leases: a per-process snapshot of claims, written behind and read once at startup

- Status: accepted
- Date: 2026-11-03

## Context

Phase 4 holds a session's names and ports for its principal for a resume window (120 s) after the
session ends (ADR-0014), so a reconnecting client gets them back and nobody else can take them in
between. Holds live in the route table and the port pool, in memory. That covers a dropped connection,
but not the gap phase 4 exists for: a deploy. JBurrow runs one pod with host networking and
`Recreate`, so a deploy replaces the process, and with it every hold. A random name the server chose,
`quiet-otter-4f2a`, would be anyone's the moment the new process starts.

Something has to carry the holds from one process to the next. The only shared state the two
processes have is Postgres, which in production is Neon (ADR-0003): serverless, suspended when idle,
a second or two to wake, and occasionally unreachable. `PROJECT.md` sketched a lease row written at
registration, refreshed every 30 s and consulted when a name is claimed. That puts Neon on the
handshake path, which phase 1 kept free of anything slower than the authtoken check, and makes every
registration wait for a database that may be asleep or down.

Requests have never touched Postgres, and the routing table has always been in memory. Postgres was
needed to start the server (Flyway), never to keep it running.

## Decision

- **A lease is one row of a per-process snapshot.** `tunnel_lease` (`V2__tunnel_lease.sql`) has one
  row per name or port a process has claimed or holds: the process's instance id, the kind, the key,
  the principal, whether a live tunnel had it, and when it stops counting. The primary key is
  `(instance_id, kind, lease_key)`. There is no foreign key to `server_instance`, which is written
  only once the application is ready and may fail to be.
- **Written behind, on change and on a timer.** `LeaseKeeper`, in the `lease` feature of
  `jburrow-server-core`, snapshots the route table and the port pool. A scheduler in the composition
  root calls it every second on a virtual thread. It writes when either table's change counter moved,
  or when `jburrow.lease.refresh` (30 s) has passed and there are leases to keep. A write replaces
  the process's rows in one transaction. An active claim counts until `now + refresh + window`, so a
  process that dies without a word leaves its claims counting for one missed refresh and a whole
  window; a hold counts until its end. A failed write is logged at most once a minute and counted, and
  the next tick tries again with a fresh snapshot.
- **Nothing waits for a write.** Registration never calls the keeper; the keeper reads the tables'
  counters, so `tunnel` doesn't depend on `lease` at all, which an ArchUnit rule holds. The only
  write anything waits for is the final flush after the drain (06), with a 2-second timeout.
- **Read once, at startup, before the edge binds.** After Flyway and before the edge accepts a
  connection, the keeper deletes rows that stopped counting more than a day ago, reads every row of
  any process that still counts, keeps the latest `expiresAt` per key, and seeds each as a hold for its
  principal until then, active rows included: the process that wrote them is gone, and so are its
  sessions. A seeded hold never replaces a claim, and a port outside the configured range is dropped.
  If the read fails, startup fails, as it does when Flyway can't reach the database.
- **An idle server issues no query.** With nothing to keep and nothing of its own written, the keeper
  doesn't touch the store, so a server with no tunnels still lets Neon suspend.
- **The engine owns the logic, the API module the table.** What to write, when, and how to seed is
  plain Java behind the `LeaseStore` port. The JPA adapter lives in `jburrow-server-api`, a feature of
  its own, so the port speaks its own record type rather than the engine's domain `Lease`.

## Consequences

- A deploy, and a crash, give every owner back its names and ports for the window. After a crash, a
  claim made since the last write (at most 30 s before) isn't protected, though it is usually still
  free.
- A Neon wake-up or outage delays no registration and no request. An outage while the server runs
  only stops the snapshot from moving; a restart inside it loses the claims made since the last good
  write.
- While tunnels are open, the 30-second refresh keeps Neon's compute awake (`PROJECT.md` §Open
  questions). Revisit the plan, or the interval, once usage is real.
- Leases name principals, as logs already do. Rows are deleted a day after they stop counting.
- Leases are not reservations and not the routing table. Phase 5's reserved subdomains are a table
  of their own, with no expiry, checked when a name is claimed.
- The per-instance rows are already the shape a second node would need, if leases ever become a
  shared registry (`ROADMAP.md`, Later).
- Phase 4's final flush (checkpoint 06) depends on the drain having ended every session first, so
  the last snapshot holds every claim as a hold.

## Alternatives considered

- **Writing a lease at registration.** Every handshake would wait on Neon: a second or two when it is
  asleep, and a failed registration when it is down, for a row that only matters if the process dies
  in the next few seconds.
- **Consulting leases at registration.** The same wait, once per claim, and a second source of truth
  beside the in-memory table that both registration and routing already trust.
- **Postgres as the routing table.** Every request would depend on the database; a Neon outage would
  take every tunnel down, not just new ones.
- **No persistence, holds in memory only.** Simple, and a deploy would hand every random URL to
  whoever asked first. The phase's exit criterion is a deploy that keeps the URL.
- **Storing resume tokens instead.** Rejected in ADR-0014: a secret in the database that buys nothing
  a hold by principal doesn't.
