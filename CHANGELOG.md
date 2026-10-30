# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project
adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- Maven reactor with six modules (`jburrow-protocol`, `jburrow-server-core`, `jburrow-server-api`,
  `jburrow-server`, `jburrow-client-core`, `jburrow-cli`), the Maven wrapper, Spotless, the
  Enforcer rules and build identity (`git.properties`, `build-info.properties`).
- ArchUnit layer rules for the server and client sides, each proven by a violating fixture
  ([ADR-0002](docs/adr/0002-single-deployable-hexagonal-modules.md)).
- Postgres through Flyway in a `jburrow` schema, with a connection pool that drains when idle
  ([ADR-0003](docs/adr/0003-managed-postgres-neon.md)).
- Every server start is recorded in `server_instance`; `GET /api/v1/meta` returns the running
  instance's id, version, commit and start time.
- `docker-compose.yml` (Postgres, Mailpit) and a `Makefile` for local development.
- Outbound mail through an `OutboundMail` port and one SMTP adapter, with the SES configuration-set
  header when configured and an opt-in startup smoke test
  ([ADR-0004](docs/adr/0004-outbound-mail-over-smtp.md)).
- Liveness and readiness health groups that leave the database and mail out, graceful shutdown
  with a 20-second phase timeout, Prometheus metrics tagged with the application and instance,
  and a `jburrow_build_info{version,commit}` metric.
- ECS JSON logs in the `prod` profile carrying the service name, version and instance id.
- `jburrow version` and `jburrow --version`, from a fat JAR.
- A layered, non-root `jburrow-server` container image built from `jburrow-server/Dockerfile`
  with only Docker (`make image`), carrying its commit without a `.git` directory in the build
  context.
- A GitHub Actions check on every pull request and push to `main` that runs `./mvnw verify` and
  builds the server image without pushing it, with a read-only token and no secrets, plus
  Dependabot for Maven and Actions, and issue and pull request templates
  ([ADR-0005](docs/adr/0005-ci-trust-boundary-public-repository.md)).
- A `Jenkinsfile` that verifies `main`, pushes the server image and commits its tag to a GitOps
  repository, reading every deployment value from job parameters and failing fast on a missing
  one.
- The wire protocol, `jburrow.v1-draft.2`, specified in [`docs/protocol.md`](docs/protocol.md)
  with valid and invalid conformance vectors: a 10-byte frame header over one binary WebSocket
  message per frame, strict JSON control payloads, per-stream and per-session credit windows that
  each side announces in its `HELLO`, and one error code space shared with the REST API
  ([ADR-0007](docs/adr/0007-wire-protocol-v1.md), [ADR-0009](docs/adr/0009-flow-control.md)).
  `jburrow-protocol` holds the pure-Java model, the stream, flow-control and liveness rules, and
  the Netty codec. Draft 1 peers are refused at the upgrade with `UNSUPPORTED_PROTOCOL_VERSION`.
- Jazzer fuzz targets for the frame codec, the control JSON and the stream rules, run by
  `make fuzz` and by a `fuzz` check on every pull request.
- `JBurrowClient` in `jburrow-client-core`: the client agent, embeddable in a test or another
  Java program without the CLI.
- The edge listener on `:7080`: clients connect over WebSocket at `/v1/connect`, register TCP
  tunnels and get a public port from `jburrow.tcp.port-range`, with session, handshake and
  per-owner caps, liveness, backpressure and graceful drain
  ([ADR-0008](docs/adr/0008-byte-path-concurrency-model.md)). Tunnel metrics and an `edge` health
  indicator.
- Many streams per session: up to `jburrow.edge.max-concurrent-streams` (1,000, announced in the
  server `HELLO`) and `jburrow.edge.max-streams` (4,000) across the server, with the server's
  receive windows set by `jburrow.edge.stream-window` and `jburrow.edge.session-window`. The edge
  logs at startup the direct memory its settings can make it hold, and warns above 90% of the limit.
  `jburrow.tcp.visitor-receive-buffer` (32 KiB) caps what each visitor held back by credit can park
  in the server's kernel, and the edge logs that total at startup too
  ([ADR-0010](docs/adr/0010-share-floor-and-visitor-receive-buffers.md)). A tunnel's listener queues
  up to 1,024 connections waiting to be accepted, so a burst of visitors isn't kept waiting on the
  kernel's handshake retries. The "session closed" line logs `maxStreams`, the most streams the
  session had open at once.
- Per-stream backpressure on the server: a visitor is read only as far as its stream may send, and
  credit for the client's `DATA` goes back as it is written to the visitor, so a slow visitor holds
  back only its own stream and the client's connection is always read. Visitors waiting for a
  session's credit take turns at it, one read per turn, each taking an equal share of it but never
  less than 16 KiB, so a long line doesn't split the credit into tiny frames.
  `jburrow_flow_blocked_total{scope}` counts visitors paused for want of stream or session credit,
  and the "stream closed" line logs `blockedMs`, how long the stream waited for it.
- Per-stream backpressure on the client, the server's mirror: it announces its receive windows (256
  KiB per stream, 1 MiB per session, settable with `JBurrowClient.Builder.windows`), reads a local
  connection only as far as its stream may send, and returns credit as the server's `DATA` is
  written to the local service. A local service that stops reading holds back only its own stream,
  and the client never stops reading its control connection.
- `scripts/load/StreamLoad.java`, a single-file load driver run by hand: many concurrent
  connections through a tunnel to an echo service, every byte checked by SHA-256, with stalled
  readers and a latency probe for head-of-line tests. `scripts/echo-server.py` accepts a backlog of
  1,024 connections for it.
- Static authtokens from `jburrow.auth.static-tokens` (`name:sha256hex`, so configuration never
  holds a usable token), and `make authtoken NAME=<name>` to mint one.
- `jburrow tcp [host:]port`: exposes a local TCP port through a public port on the server, logs
  each connection, and stops on Ctrl-C with exit code 0. `--output json` writes one event per
  line, ending with a `closed` event. Exit codes 2 to 6 tell usage errors, a refused authtoken, a
  lost connection, a refused registration and a protocol mismatch or violation (such as
  `FLOW_CONTROL_ERROR`) apart. The authtoken comes from `JBURROW_AUTHTOKEN` or `--authtoken`.
- The `http` capability of protocol draft 2, specified in [`docs/protocol.md`](docs/protocol.md)
  §4.2, §7.9, §7.10 and §9.7 with conformance vectors for sessions with and without it: HTTP
  tunnels on the wire, one stream per request, the request head in `OPEN`, response heads in
  `HEADERS`, both bodies as `DATA` without their HTTP/1.1 framing, and the codes
  `SUBDOMAIN_UNAVAILABLE`, `SUBDOMAIN_FORBIDDEN` and `LOCAL_SERVICE_ERROR`
  ([ADR-0011](docs/adr/0011-http-tunnels.md)). A peer that doesn't offer `http` sees draft 2
  unchanged. `jburrow-protocol` gains the heads as
  validated records, `HttpExchangeRules` for the order and body length of an exchange, and a Jazzer
  target for them.
- Reservations and parking in the flow model
  ([ADR-0012](docs/adr/0012-http-exchanges-across-event-loops.md)). A reservation takes send
  credit before a read and refunds what the read didn't use. Parking returns a stalled stream's
  session credit before its bytes are written, but not its stream credit. `SessionFlowFuzzTest`
  covers both.
- The `routing` feature for HTTP tunnel names, for registration to come: `SubdomainName` in the
  protocol model, the built-in blocklist of reserved names and phishing terms, random
  `<adjective>-<animal>-<hex>` names, and the in-memory route table, which keeps a released name
  held for its owner for a while.
- HTTP tunnel registration on the server: a session negotiates the `http`
  capability from what its client offers and the server enables, `REGISTER` claims each HTTP
  tunnel's requested or random name all or nothing with its ports and answers with
  `http(s)://<name>.<domain>`, refusals carry `SUBDOMAIN_FORBIDDEN` or `SUBDOMAIN_UNAVAILABLE` and
  the tunnel's `ref`, and a session's names go on hold when it ends.
- The `jburrow.http.*` settings, validated at startup: the tunnel domain (required in `prod`, as
  `JBURROW_HTTP_DOMAIN`), the public scheme and port, extra reserved names
  (`JBURROW_HTTP_RESERVED_NAMES`), the name hold, and the request, timeout, socket buffer and
  parking limits the edge will use. The labels of the connect and TCP hosts under the domain are
  reserved, and a service host nested deeper under it stops startup.
- The edge reads each request's `Host` before deciding what the request is for. The connect host
  gets phase 1's upgrade handlers, installed per request and otherwise unchanged; a host one label
  under `jburrow.http.domain` goes to the exchange handler; anything
  else gets a `404` page. One exchange runs at a time per connection, an idle connection between
  exchanges is closed after `jburrow.http.keep-alive-timeout`, and a connection that carries
  tunnel requests gets `jburrow.http.socket-buffer` as its socket buffers.
- Error pages for requests the edge answers itself: a short HTML page with no script or external
  reference, naming the host (never the path) and a stable code that the `jburrow-error` header
  repeats, with `Retry-After` where it applies.
- HTTP exchanges on the server, which now lists `http` in its `HELLO`. Each request for a tunnel
  host becomes a stream on the session serving its name, with the visitor's address in
  `x-forwarded-for` (from trusted proxies only), `x-forwarded-proto`, `x-forwarded-host` and
  `via: 1.1 jburrow` set by the edge, and hop-by-hop and visitor-sent forwarding fields removed.
  The request body is read from the visitor only against credit already reserved for it, at most 16
  KiB at a time, and the response comes back with its framing set by the edge and `Domain` removed
  from every `set-cookie`. The edge answers `Expect: 100-continue` itself and passes `1xx` heads on.
  A response whose visitor stops reading for `jburrow.http.park-after` is parked within
  `jburrow.http.max-parked` and `max-parked-per-session`, so it stops holding back the tunnel's
  other requests. Requests get `504` after `jburrow.http.response-timeout`, `502` when the local
  service fails, `503` with `Retry-After` at a stream cap or while the server stops, and `413` for
  a chunked body that passes `jburrow.http.max-request-body`. Clients that don't offer `http` see
  draft 2 unchanged. The startup budget line adds the parked bytes, and the kernel line names
  exchanges' socket buffers; the `local` profile leaves those to the kernel, since on loopback a
  16 KB buffer is smaller than one segment.
- HTTP tunnels in `JBurrowClient`: `JBurrowClient.Builder.http(localTarget)`, with optional
  `subdomain(name)` and `hostHeader(HostHeader.REWRITE)`. The client offers `http` in every
  `HELLO`, and sends an HTTP tunnel's `REGISTER` once the server's `HELLO` has listed it; against a
  server that doesn't, `start()` fails with `TunnelStartException.Kind.UNSUPPORTED_BY_SERVER`
  before anything is registered. Each request becomes one HTTP/1.1 exchange with the local service
  on a connection of its own, both bodies under the same flow control as TCP streams. A response
  that isn't valid HTTP/1.1, or a connection that ends before its response does, resets the stream
  with `LOCAL_SERVICE_ERROR`; a response that is complete before the request body ends cancels the
  rest of the body. `TunnelEvents.onExchangeCompleted` reports each exchange's method, target,
  status, body bytes and duration, never a header value or a body.
- `jburrow http [host:]port`: exposes a local HTTP service at a public URL, with `--subdomain
  <name>` (lower-cased, checked before connecting) and `--host-header rewrite`. It prints a line
  per request when its response ends (time, method, target cut to 60 characters, status, response
  body size and duration, or `-` and the reason for a request reset before its response), and
  `--output json` writes a `request` event per exchange. A subdomain in use or not allowed exits 5
  naming it, and a server without HTTP tunnels exits 6. `jburrow tcp` and `jburrow http` share
  their options and their run loop.
- Observability for HTTP tunnels: `jburrow_http_requests_total` by status class and origin,
  `jburrow_http_edge_errors_total` by page code, `jburrow_http_response_head_seconds`,
  `jburrow_flow_parked_bytes`, `jburrow_flow_parked_total`, `jburrow_flow_park_refused_total` and
  `jburrow_subdomain_claims_total` by outcome, and an INFO line per exchange with its tunnel, host,
  method, status, origin, body bytes and timings, never its target, headers or bodies.
- `JBurrowClient.Builder.capabilities(set)`, to offer fewer capabilities than the client implements.
- The HTTP end-to-end suite, on epoll and NIO: the method and status matrix, routing per request on
  shared connections, 100 MiB bodies both ways with flat memory, paused readers with and without
  parking, server-sent events, smuggling and forwarding headers, every error page, a client without
  `http`, the new metrics, and log hygiene read from ECS JSON.
- `scripts/http-echo-server.py`, an HTTP/1.1 service on the Python standard library for the smoke
  test, the load driver and trying HTTP tunnels by hand: `/hello`, `/echo` (chunked back if sent
  chunked), `/bytes/<n>`, `/sse` and `/stall`.
- `StreamLoad --http`: requests to an HTTP tunnel over raw sockets, one connection each, as uploads
  echoed back (by length or chunked), downloads checked against the echo service's pseudo-random
  stream, or `GET /hello`; stalled responses beside a probe; `http` and `https` URLs. The native
  smoke test runs it briefly. Phase 3's measurements are in its ARCHITECTURE.md §Performance.
- A `native` check on every pull request that builds `jburrow` as a GraalVM native binary on Linux
  x64 and runs real tunnels with it, publishing nothing: a TCP tunnel with 50 concurrent streams
  on one session and then one 4 MiB stream, and an HTTP tunnel with 50 concurrent requests (256 KiB
  uploads echoed back, half of them chunked, and 1 MiB downloads), every body checked by SHA-256,
  and a server-sent event stream
  ([ADR-0006](docs/adr/0006-cli-distribution-native-binaries.md)). `make native` builds the binary
  locally and `make native-smoke` runs the same test.
