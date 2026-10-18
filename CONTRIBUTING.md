# Contributing to JBurrow

JBurrow is a build-in-public project. People follow along and, once a piece gets interesting, send
pull requests. This document is how the build fits together and what a change needs before it
merges.

Some sections describe tooling that doesn't exist yet. They are marked *added in phase 0* and
become true as phase 0 lands; until then, the repository holds documentation only.

## Prerequisites

- JDK 21 (Temurin recommended).
- Docker, for the local Postgres and Mailpit and for the integration tests (Testcontainers).
- Nothing else. The Maven wrapper (`./mvnw`) is committed, so Maven doesn't need to be installed.

## The build: one reactor

JBurrow is one Maven multi-module reactor under a single parent POM. Its modules are one
product, released together under one version number. The parent POM pins every dependency and
plugin version; module POMs carry none.

```bash
make up          # Postgres on :5432, Mailpit on :1025 (inbox UI on :8025), from docker-compose.yml
make run         # the server with the local profile: API on :7090, management on :7091
make verify      # ./mvnw clean verify: unit, architecture and integration tests (Docker required)
make test        # unit and architecture tests only, no containers
make format      # apply Spotless formatting
make down        # stop the local dependencies (make reset also deletes their data)
```

The local profile connects to the compose Postgres as `jburrow`/`jburrow`. Those credentials only
unlock the local container. Flyway migrates the `jburrow` schema on startup; migrations live in
`jburrow-server-api/src/main/resources/db/migration` and are never edited once applied. Add a new
one instead.

The `instance` feature in `jburrow-server-api` (`server_instance`, `GET /api/v1/meta`) is the
reference slice: domain, use cases, ports, a JPA adapter, a web adapter, and wiring in
`jburrow-server`'s `config` package. A new feature copies its shape.

Run `./mvnw clean verify` yourself before opening a pull request, and confirm it's green.

## Modules

| Module | Holds |
|---|---|
| `jburrow-protocol` | Frame definitions, the binary codec, control messages, error codes. No server or client code |
| `jburrow-server-core` | The tunnel engine: sessions, routing, streams, flow control, limits. No Spring, JPA or Postgres |
| `jburrow-server-api` | Accounts, tokens, reservations, abuse controls; implements core's outbound ports against Postgres |
| `jburrow-server` | `main()`, Spring configuration, the composition root, the Dockerfile |
| `jburrow-client-core` | The client agent: connection, registration, streams, local forwarding, reconnect |
| `jburrow-cli` | The `jburrow` command line: parsing, output, configuration file |

Dependencies point one way. The server and client sides never depend on each other; their only
shared contract is `jburrow-protocol`. See [`PROJECT.md`](PROJECT.md) §Module catalog.

## Layer rules

Every server and client module is organised by feature first, then by layer
(`<feature>/domain`, `<feature>/application`, `<feature>/application/port`,
`<feature>/adapter/<technology>`):

- `domain` depends only on the JDK and other `domain` packages.
- `application` depends on `domain` and its own `port` interfaces. No Netty, Spring or JPA. Use
  cases are plain classes constructed by the composition root in `jburrow-server`.
- Netty lives only in `adapter.netty`, Spring MVC only in `adapter.web`, JPA only in
  `adapter.persistence`.
- A feature reaches another feature only through that feature's `application.port` interfaces.
- No cycles between features. No `@Transactional` in `application` code; transactions are applied
  in the composition root.

*Added in phase 0:* each rule is an ArchUnit test, so breaking one fails `./mvnw verify`, not a
review. If a rule is in your way, the change probably belongs in a different layer. If you think
the rule itself is wrong, propose that in an ADR. See [`PROJECT.md`](PROJECT.md) §Clean
architecture rules.

## Tests

- Unit tests are named `*Test` and run under Surefire.
- Integration tests are named `*IntegrationTest` and run under Failsafe in `verify`.
- Postgres and Mailpit are real containers (Testcontainers) in integration tests. Don't mock them,
  and don't use H2. Import `TestContainersConfiguration` (in `jburrow-server`'s test sources)
  rather than declaring another container.
- Don't disable a failing test or skip integration tests to get green. Fix the cause.

[`PROJECT.md`](PROJECT.md) §Testing lists every level, from codec tests to the in-process end-to-end
suite.

## Code style

- *Added in phase 0:* Java is formatted with [Palantir Java Format](https://github.com/palantir/palantir-java-format)
  through the Spotless Maven plugin. `spotless:check` is bound to `verify`; `make format` fixes
  drift. Don't hand-format around it.
- Whitespace, line endings and charset are defined once in `.editorconfig`.
- No comments that restate the code. Keep them for a hidden constraint, a workaround or a
  non-obvious invariant.
- Prefer editing an existing class over a new abstraction for a one-off need.

## Spring Boot 4 notes

JBurrow is on Java 21 and Spring Boot 4.1. If you copy a snippet written for Boot 3:

- Starters are modular: `spring-boot-starter-webmvc`, not `-web`.
- Jackson 3: `ObjectMapper` and databind are under `tools.jackson.*`; annotations stay under
  `com.fasterxml.jackson.annotation`.
- Testcontainers 2.x artifact ids are prefixed, for example `testcontainers-postgresql`.

## Architecture decisions

A change that alters an architectural decision gets an ADR in the same pull request. Copy
[`docs/adr/0000-adr-template.md`](docs/adr/0000-adr-template.md), take the next number, and add it
to the index in [`docs/adr/README.md`](docs/adr/README.md).

## Continuous integration

Two systems build this code ([ADR-0005](docs/adr/0005-ci-trust-boundary-public-repository.md)).

- **GitHub Actions** ([`.github/workflows/verify.yml`](.github/workflows/verify.yml)) runs on every
  pull request, including from forks, and on every push to `main`. The `verify` job runs
  `./mvnw verify` and uploads the test reports; the `image` job then builds the server image from
  `jburrow-server/Dockerfile` and discards it. Both must pass before a pull request merges. The
  workflow has a read-only token and no secrets.
- **The maintainers' pipeline** builds and deploys `main` only, and never runs code from a pull
  request. Its deployment details are configured on the job, not in this repository.

The [`Jenkinsfile`](Jenkinsfile) is that pipeline, and it's reusable as it stands. It runs
`./mvnw verify` in a Maven container with the agent's Docker socket mounted for Testcontainers,
builds the server image, pushes `<IMAGE_REPOSITORY>:<build>-<sha>` and `:main`, then commits the
new tag to a GitOps repository for Argo CD or Flux to pick up. Every target comes from job
parameters, listed at the top of the file: `IMAGE_REPOSITORY`, `REGISTRY_CREDENTIALS_ID` (username
and password), `GITOPS_REPO_URL`, `GITOPS_BRANCH`, `GITOPS_MANIFEST_PATH`, `GITOPS_CREDENTIALS_ID`
(secret text), `GITOPS_COMMIT_NAME` and `GITOPS_COMMIT_EMAIL`. Set them as string parameters with
default values on the job, since a webhook-triggered build runs with the defaults. The first stage
fails with the names of any that are empty. The file declares no `parameters {}` block on purpose:
one would overwrite the job's configured values on its next run.

If you're a first-time contributor, a maintainer approves the workflow run on your pull request
before it starts. When `verify` fails, the `test-reports` artifact on the run holds the Surefire
and Failsafe reports.

Never add a secret, a registry login, a deploy step or a `pull_request_target` trigger to a
workflow. Third-party actions are pinned to full commit SHAs with the version in a comment, and
Dependabot opens the pull requests that move them.

## Pull requests

- Branch off `main`. Keep a pull request to one change.
- Run `./mvnw clean verify` locally first, with Docker running.
- Never commit a real secret. See [`SECURITY.md`](SECURITY.md).
- Add an entry under `## [Unreleased]` in [`CHANGELOG.md`](CHANGELOG.md) for a user-visible change.

## Code of conduct

Everyone taking part is expected to follow the [Code of Conduct](CODE_OF_CONDUCT.md).
