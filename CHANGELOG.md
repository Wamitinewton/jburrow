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
