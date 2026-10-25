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
