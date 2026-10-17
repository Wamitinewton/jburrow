# 2. Single deployable, one reactor, hexagonal feature modules

- Status: accepted
- Date: 2026-10-17

## Context

JBurrow's hard problems all live inside one process: multiplexing thousands of streams over one
connection, flow control, routing by `Host` header, holding a session through restarts, and abuse
controls on one public IP (`PROJECT.md` §Why this project). Splitting the server into services
would add network hops without solving any of them, and the session registry and port bindings
mean exactly one server process runs at a time anyway.

Pallet, the sibling project, builds each of its services as an independent Maven project with its
own version and release, because its services deploy and evolve separately. JBurrow's modules are
one product: the protocol, server and client change together and ship under one version number.

The code still needs hard boundaries. The server and client may only share the wire protocol; a
third-party client should be able to depend on `jburrow-protocol` alone; and the tunnel engine and
use cases must be testable without Spring, Netty or a database. Conventions that only live in a
document erode, so the boundaries have to be checked by the build from the first commit, while
there is nothing yet to violate them.

## Decision

- **One deployable server**, built from **one Maven reactor** under a single parent POM
  (`io.jburrow:jburrow-parent`) that pins every dependency and plugin version. Module POMs carry
  none.
- **Six modules with one-way dependencies**: `jburrow-protocol`; `jburrow-server-core` →
  protocol; `jburrow-server-api` → server-core; `jburrow-server` (composition root) → server-api,
  server-core; `jburrow-client-core` → protocol; `jburrow-cli` → client-core. Server and client
  sides never depend on each other.
- **Feature first, then layer** inside each module: `<feature>/{domain, application,
  application/port, adapter/<technology>}`. `domain` and `application` are plain Java; frameworks
  appear only in adapters; use cases are constructed and wrapped in transactions by the composition
  root.
- **Enforced by ArchUnit** in `jburrow-server` (`JBurrowArchitectureRules`, applied to every
  server and protocol class) and `jburrow-cli` (the client-side rules). Every rule has a fixture
  that must violate it, so a rule that silently matches nothing fails its own test.

## Consequences

- A misplaced import fails `./mvnw verify` with the rule's name in the message, not in review.
- JPA entities are adapter types mapped to and from domain objects, which costs mapping code in
  every persistence adapter.
- The composition root wires use cases to their ports by hand instead of through component
  scanning, so each new use case means one more bean definition in `jburrow-server`.
- Releases are one version for everything. Publishing `jburrow-protocol` on its own stays possible
  because it depends on nothing else in the reactor.
- A feature reaching another feature has to go through that feature's `application.port`. A port
  whose signatures use another feature's domain types will need that rule revisited when the first
  real case appears.

## Alternatives considered

- **Pallet-style independent projects per module.** Separate versions and release pipelines buy
  independent releases, which JBurrow doesn't need, at the cost of cross-project version bumps for
  every protocol change.
- **A single module, layered by packages only.** Simpler to build, but nothing at compile time
  stops server code from importing client code, and the protocol can't be published alone for
  third-party clients.
- **Microservices** (edge, control plane, accounts). Adds network hops and distributed failure
  modes to a system whose state is a single in-memory session registry, and solves none of its
  actual problems.
