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
- The wire protocol, `jburrow.v1-draft.1`, specified in [`docs/protocol.md`](docs/protocol.md)
  with valid and invalid conformance vectors: a 10-byte frame header over one binary WebSocket
  message per frame, strict JSON control payloads, and one error code space shared with the REST
  API ([ADR-0007](docs/adr/0007-wire-protocol-v1.md)). `jburrow-protocol` holds the pure-Java
  model, the stream and liveness rules, and the Netty codec.
- Jazzer fuzz targets for the frame codec, the control JSON and the stream rules, run by
  `make fuzz` and by a `fuzz` check on every pull request.
- `JBurrowClient` in `jburrow-client-core`: the client agent, embeddable in a test or another
  Java program without the CLI.
- The edge listener on `:7080`: clients connect over WebSocket at `/v1/connect`, register TCP
  tunnels and get a public port from `jburrow.tcp.port-range`, with session, handshake and
  per-owner caps, liveness, backpressure and graceful drain
  ([ADR-0008](docs/adr/0008-byte-path-concurrency-model.md)). Tunnel metrics and an `edge` health
  indicator.
- Static authtokens from `jburrow.auth.static-tokens` (`name:sha256hex`, so configuration never
  holds a usable token), and `make authtoken NAME=<name>` to mint one.
- `jburrow tcp [host:]port`: exposes a local TCP port through a public port on the server, logs
  each connection, and stops on Ctrl-C with exit code 0. `--output json` writes one event per
  line, ending with a `closed` event. Exit codes 2 to 6 tell usage errors, a refused authtoken, a
  lost connection, a refused registration and a protocol mismatch apart. The authtoken comes from
  `JBURROW_AUTHTOKEN` or `--authtoken`.
- A `native` check on every pull request that builds `jburrow` as a GraalVM native binary on Linux
  x64 and pushes 4 MiB through a real tunnel with it, publishing nothing
  ([ADR-0006](docs/adr/0006-cli-distribution-native-binaries.md)). `make native` builds the binary
  locally and `make native-smoke` runs the same test.
