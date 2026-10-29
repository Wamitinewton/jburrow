# Contributing to JBurrow

JBurrow is a build-in-public project. People follow along and, once a piece gets interesting, send
pull requests. This document explains how the build fits together and what a change needs before
it merges.

Some sections describe tooling that doesn't exist yet. They are marked *added in phase 0* and
become true as phase 0 lands; until then, the repository holds documentation only.

## Prerequisites

- JDK 21 (Temurin recommended).
- Docker, for the local Postgres and Mailpit and for the integration tests (Testcontainers).
- Nothing else. The Maven wrapper (`./mvnw`) is committed, so you don't need to install Maven.

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
make authtoken NAME=alice   # print a new authtoken and its jburrow.auth.static-tokens entry
make down        # stop the local dependencies (make reset also deletes their data)
```

The local profile connects to the compose Postgres as `jburrow`/`jburrow`. Those credentials only
unlock the local container. Flyway migrates the `jburrow` schema on startup. Migrations live in
`jburrow-server-api/src/main/resources/db/migration`, and once one has been applied, nobody edits
it. Add a new one instead.

The `instance` feature in `jburrow-server-api` (`server_instance`, `GET /api/v1/meta`) is the
reference slice: domain, use cases, ports, a JPA adapter, a web adapter, and wiring in
`jburrow-server`'s `config` package. A new feature copies its shape.

### Authtokens in development

Until accounts arrive (phase 5), the server accepts a fixed list of named tokens from
`jburrow.auth.static-tokens`. Each entry is `name:sha256hex`, the SHA-256 of the full token, so
configuration never holds a usable token. The `local` profile configures one entry, `local`, for
the documented development token:

```
jb_LOCAL-DEVELOPMENT-ONLY-unlocks-localhost-00
```

It unlocks a server on your own machine and nothing else. Never use it anywhere else, and never
commit any other token. For a server that others can reach, `make authtoken NAME=<name>` prints a
new random token and its entry: hand the token to its owner (it is shown once and stored nowhere)
and add the entry to the deployment's `JBURROW_AUTH_STATIC_TOKENS`, comma-separated. The `prod`
profile has no default; with an empty list, every registration is refused.

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

- `domain` depends only on the JDK, other `domain` packages and the protocol model.
- `application` depends on `domain`, its own `port` interfaces and the protocol model. No Netty,
  Spring or JPA. Use cases are plain classes constructed by the composition root in
  `jburrow-server`.
- *Added in phase 1:* the protocol model, `io.jburrow.protocol` outside its `adapter` packages, is
  pure Java and depends only on the JDK and itself. It is the vocabulary both sides share, which is
  why core layers may use it (ADR-0007).
- Netty lives only in `adapter.netty`, Spring MVC only in `adapter.web`, JPA only in
  `adapter.persistence`.
- A feature reaches another feature only through that feature's `application.port` interfaces.
- No cycles between features. No `@Transactional` in `application` code; the composition root
  applies transactions.

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

### Fuzzing

The code that reads untrusted bytes (the frame codec, the control payload reader and the stream state
machine) has [Jazzer](https://github.com/CodeIntelligenceTesting/jazzer) fuzz targets, the
`*FuzzTest` classes in `jburrow-protocol/src/test/java/io/jburrow/protocol/fuzz/`. They run in two
modes:

- **Regression**, in every build: each target replays the files in its inputs directory,
  `src/test/resources/io/jburrow/protocol/fuzz/<Class>Inputs/<method>/`. You don't need to do
  anything.
- **Fuzzing**, with `make fuzz`: each target runs for its `@FuzzTest(maxDuration)`, two minutes,
  in its own JVM, since Jazzer fuzzes one target per JVM. The `fuzz` CI job runs the same command
  on every pull request. For a longer local run, raise `maxDuration` temporarily and don't commit
  it.

The inputs directories hold two kinds of file:

- **Seeds**, one per conformance vector in `io/jburrow/protocol/vectors/`, named after it.
  `SeedCorpusTest` fails if they drift from the vectors. After adding or changing a vector, run
  `./mvnw -pl jburrow-protocol test -Dtest=SeedCorpusTest -Dupdate.seeds=true` and commit the
  result.
- **Findings**, named `finding-<description>`. They stay for ever.

When fuzzing finds something, Jazzer writes the input as `crash-<sha1>` into the target's inputs
directory (or into `jburrow-protocol/` if it can't find the source tree). In CI it's in the
`fuzz-findings` artifact of the failed run. To turn it into a regression input:

1. Put the file in the target's inputs directory and rename it `finding-<what it does>`.
   `SeedCorpusTest` refuses any other name. The target now fails in every build.
2. Fix the cause. If the fix adds or clarifies a receiver rule, add a conformance vector and
   update the receiver rules in `docs/protocol.md` §13.
3. Keep the finding in the corpus.

### The native CLI binary

`jburrow` ships as a GraalVM native binary ([ADR-0006](docs/adr/0006-cli-distribution-native-binaries.md)),
and every pull request proves it still builds and still tunnels bytes. You don't need GraalVM for
day-to-day work; you need it when the `native` CI job fails on your change.

```bash
sdk install java 21-graalce      # or any GraalVM for JDK 21, as the JDK Maven runs on
make native                      # jburrow-cli/target/jburrow, beside the fat JAR
./jburrow-cli/target/jburrow version
make native-smoke                # builds the server JAR too, then runs scripts/native-smoke.sh
```

`scripts/native-smoke.sh` is what CI runs (Linux; needs Docker, `python3`, `jq` and `curl`). It
starts Postgres and the server from its JVM build with the `local` profile, on ports of its own
(edge 17080, API 17090, management 17091, tunnels 21100-21199) so it runs beside `make run`. It
then starts `scripts/echo-server.py` on port 9000, opens a tunnel to it with the native binary
using the development token, pushes 4 MiB of random bytes through the tunnel, and compares SHA-256
of what went out and what came back. Finally it stops the CLI with `SIGINT`, as Ctrl-C would, and
expects exit status 0 and a final `closed` event with reason `USER_CLOSED`. Logs land in
`target/native-smoke/` and are printed on failure. `JBURROW_BIN=<path>` runs the same scenario
against another binary.

For load rather than correctness, `scripts/load/StreamLoad.java` drives many concurrent streams
through a running tunnel and checks every byte; [`scripts/load/README.md`](scripts/load/README.md)
covers running it and the scenarios behind phase 2's measurements. It is run by hand and is not
part of the build.

Native-image configuration lives with the code that needs it, and is picked up from each jar:

| Need | Where |
|---|---|
| picocli's commands and options | generated at compile time by `picocli-codegen` |
| Netty | Netty's own jars ship their configuration; add nothing for Netty unless a build or the smoke test proves it necessary |
| Jackson reading and writing protocol payloads | `jburrow-protocol/.../reflect-config.json`, checked against the records by its `NativeImageMetadataTest` |
| Jackson writing the CLI's `--output json` events | `jburrow-cli/.../reflect-config.json`, checked by the CLI's `NativeImageMetadataTest` |
| `client.properties` (client version) | `jburrow-client-core/.../resource-config.json` |
| `jburrow-cli.properties`, `git.properties` (build info), `simplelogger.properties` | `jburrow-cli/.../resource-config.json` |
| Class initialization and signal handling | `jburrow-cli/.../native-image.properties`, each flag with the reason for it |

(`...` is `src/main/resources/META-INF/native-image/io.jburrow/<module>`.) GraalVM's reachability
metadata repository is off in the `native` profile: the plugin's current repository needs a newer
GraalVM than the last Community build for JDK 21, and it only holds metadata for older versions of
Netty and Jackson.

Netty's own configuration initializes all of `io.netty` at build time. That is why
`native-image.properties` moves single classes back to run time, and initializes the SLF4J
classes that Netty's static loggers bind to at build time. If a change adds a Netty class whose
static initializer does I/O or allocates buffers, the build fails with "Classes that should be
initialized at run time got initialized during image building". Rerun the `native-image`
command from the failed build's log with `--trace-class-initialization=<class>`, and move the
class the trace points at to run time.

**When the native build or the smoke test fails on missing metadata** (a `ClassNotFoundException`,
a missing resource, or an empty JSON event in the native binary only), record what the JVM build
does under GraalVM's tracing agent, running the same scenario:

```bash
java -agentlib:native-image-agent=config-output-dir=jburrow-cli/target/agent-config \
     -jar jburrow-cli/target/jburrow-cli-*.jar tcp 9000 --server ws://localhost:7080/v1/connect --output json
```

Then copy **only** the entries that explain the failure into the right module's file above, and
extend that module's `NativeImageMetadataTest` if the entry belongs to a family it checks. Never
commit the agent's whole output: it records everything the run touched, including JDK internals
and test-only paths, and turns the configuration into something nobody can review. Don't silence
an initialization error with `--initialize-at-build-time` for a whole package; find the class.
Don't add `netty-tcnative` or the epoll transport to the client to help native-image: the client
is NIO with the JDK's TLS everywhere.

## Code style

- *Added in phase 0:* Java is formatted with [Palantir Java Format](https://github.com/palantir/palantir-java-format)
  through the Spotless Maven plugin. `spotless:check` is bound to `verify`; `make format` fixes
  drift. Don't hand-format around it.
- `.editorconfig` defines whitespace, line endings and charset.
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
  `jburrow-server/Dockerfile` and discards it. The `fuzz` job runs `make fuzz` (see
  [Fuzzing](#fuzzing)) and uploads any crashing input. The `native` job builds the CLI as a
  GraalVM native binary and runs `scripts/native-smoke.sh` with it (see
  [The native CLI binary](#the-native-cli-binary)), uploading the logs if it fails. All four must
  pass before a pull request merges. The workflow has a read-only token and no secrets.
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

## Using AI tools

You may use AI tools. If you do, say where and how in the pull request description: which files or
parts of the change an AI tool wrote, and what you did to check them. Editor autocompletion that
finishes a line or a call doesn't count. Code you had a tool write for you does: a class, a
handler, a test suite, a feature, or a large rewrite.

We ask because most of JBurrow sits on the byte path of an internet-facing server. The frame codec
reads untrusted bytes. Session state has no locks and stays on one event loop
([ADR-0008](docs/adr/0008-byte-path-concurrency-model.md)). Flow control, port release and session
teardown depend on events happening in a fixed order, and the authtoken checks decide who can open
a public port on someone else's server. A bug in code like this often looks like working code: a
buffer that's never released, a callback on the wrong thread, a check one step too late. Tests
catch some of these and review catches the rest, but review only works when the reviewer knows
which code nobody has reasoned through line by line. Disclosure tells us where to look hardest,
and it doesn't count against your pull request.

## Pull requests

- Branch off `main`. Keep a pull request to one change.
- Disclose substantial AI-written code in the description (see [Using AI tools](#using-ai-tools)).
- Run `./mvnw clean verify` locally first, with Docker running.
- Never commit a real secret. See [`SECURITY.md`](SECURITY.md).
- Add an entry under `## [Unreleased]` in [`CHANGELOG.md`](CHANGELOG.md) for a user-visible change.

## Code of conduct

Everyone taking part follows the [Code of Conduct](CODE_OF_CONDUCT.md).
