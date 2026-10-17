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

ADR-0005 is planned for phase 0. `docs/phase-0-foundation/ARCHITECTURE.md`
§Decisions that need an ADR lists them.
