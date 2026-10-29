# Architecture Decision Records

Point-in-time decisions and their rationale. `PROJECT.md` is the living description of the system;
these capture *why* a choice was made so it isn't silently reversed later. See
[0001](0001-record-architecture-decisions.md).

| # | Decision | Status |
|---|---|---|
| [0001](0001-record-architecture-decisions.md) | Record architecture decisions | accepted |
| [0002](0002-single-deployable-hexagonal-modules.md) | Single deployable, one reactor, hexagonal feature modules | accepted |
| [0003](0003-managed-postgres-neon.md) | Managed Postgres (Neon) in production, configured for scale-to-zero | accepted |
| [0004](0004-outbound-mail-over-smtp.md) | Outbound mail over SMTP behind a port | accepted |
| [0005](0005-ci-trust-boundary-public-repository.md) | CI trust boundary for a public repository | accepted |
| [0006](0006-cli-distribution-native-binaries.md) | CLI distribution: native binaries released through JReleaser | accepted |
| [0007](0007-wire-protocol-v1.md) | Wire protocol v1: WebSocket transport, frame registry, versioning, strict receivers, one error code space, a pure protocol model | accepted |
| [0008](0008-byte-path-concurrency-model.md) | Byte-path concurrency: one event loop per session, confined state, blocking work on virtual threads, epoll on Linux, a zero-copy single-owner relay | accepted |
| [0009](0009-flow-control.md) | Flow control: stream and session credit windows announced by each receiver, credit returned for released bytes at half the window, every overrun a session error, sources read only as far as they can be sent, protocol draft 2 | accepted; amended by 0010 and 0012 |
| [0010](0010-share-floor-and-visitor-receive-buffers.md) | A floor on the read gate's share, resuming only the visitors the credit covers, bounded visitor receive buffers, 4,000 streams per server, a 30% heap | accepted |
| [0011](0011-http-tunnels.md) | HTTP tunnels as the `http` capability of draft 2: one stream per exchange, parsed heads as JSON with lower-case names, ISO 8859-1 values and no connection-specific fields, bodies without HTTP/1.1 framing, trailers and `101` reserved | accepted |
| [0012](0012-http-exchanges-across-event-loops.md) | HTTP exchanges across event loops: each half on its own loop with tasks between them, request bodies read on demand against reservations in the flow model, stalled responses parked within a budget, socket buffers per kind and a separate Service for tunnel traffic | accepted |
