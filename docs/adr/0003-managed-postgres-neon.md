# 3. Managed Postgres (Neon) in production

- Status: accepted
- Date: 2026-10-17

## Context

JBurrow needs Postgres from phase 0: the reference slice records every server start, and from
phase 4 onwards it holds accounts, authtokens, reservations and subdomain leases. The production
server runs as one pod on a single-node cluster that is shared with other workloads.

Running the database on that node would mean owning its storage, backups, upgrades and recovery on
one disk that the other workloads also depend on. The node should stay stateless, so that losing
or rebuilding it costs a redeploy, not a restore.

The server only ever sees a JDBC URL, a username and a password, so wherever production keeps its
database, self-hosters can still use any Postgres.

## Decision

Production uses **Neon**, a managed, serverless Postgres reached over the internet with TLS:

- **Direct endpoint**, not Neon's pooled (`-pooler`) one. The pooler runs PgBouncer in transaction
  mode, which breaks session-level features that Flyway and the JDBC driver rely on. One pod with
  a small pool doesn't need a server-side pooler.
- **`sslmode=require`** in the JDBC URL. The driver sends SNI, which Neon uses to route to the
  endpoint.
- **A pool that drains when idle**: `maximum-pool-size: 5`, `minimum-idle: 0`, `idle-timeout` two
  minutes, `keepalive-time: 0`, `connection-timeout` 15 seconds. Neon suspends compute after a few
  idle minutes; a pool that holds connections open would keep it awake around the clock.
- **Scale-to-zero accepted as a cold-start cost.** The first query after a suspend waits while
  compute wakes, which the 15-second connection timeout covers.
- **The database is kept out of readiness.** A readiness probe that queries every few seconds would
  keep compute awake permanently, and a short Neon outage shouldn't take the API out of service.
  Flyway runs at startup, so a pod that can't reach the database never becomes ready the first
  time. The database still appears in the aggregate health on the management port.
- Everything lives in a `jburrow` schema, migrated by Flyway on startup, with Hibernate set to
  `ddl-auto: validate`.

Locally the same server runs against `postgres:17-alpine` from `docker-compose.yml`; tests use the
same image through Testcontainers.

## Consequences

- The node holds no database state. Backups, point-in-time recovery and upgrades are Neon's job.
- Every query crosses the internet, so latency is higher than in-cluster. Pick a Neon region close
  to the node. The design already keeps the database off the hot path: tunnel routing uses an
  in-memory registry, and the database is consulted only on registration.
- Compute hours become a number to watch from phase 4, when lease refreshes keep compute awake
  while tunnels are open. Revisit the Neon plan once there is real usage.
- Nothing in the code is Neon-specific. Self-hosters set `SPRING_DATASOURCE_*` to any Postgres.

## Alternatives considered

- **In-cluster Postgres** (a StatefulSet on `local-path` storage with scheduled `pg_dump`
  backups). No per-query internet latency and no provider, but it makes the shared node stateful,
  puts backup and restore on JBurrow, and leaves the data on a single disk.
- **Neon's pooled endpoint.** It allows more client connections, but PgBouncer's transaction mode
  conflicts with Flyway's session-level locking and with JDBC prepared statements. One pod doesn't
  need it.
- **Other managed providers** (Supabase, Aiven, a cloud provider's managed Postgres). They are
  functionally equivalent for this workload. Neon's free tier with scale-to-zero, and its database
  branching, decided it.
