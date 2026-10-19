# JBurrow

An open-source, self-hostable tunneling server, in the style of ngrok, written in Java.

A developer runs `jburrow http 3000` and gets back a public HTTPS URL. Any request to that URL lands
on the JBurrow server, travels down a single long-lived connection the CLI opened earlier, and comes
out on `localhost:3000` on the developer's machine. The response goes back up the same path.
`jburrow tcp 22` does the same for raw TCP: the server allocates a public port and every connection
to it is piped to the local SSH daemon. Around the tunnel engine sit the parts of a usable service:
accounts, auth tokens, reserved subdomains, rate limits, abuse controls, metrics and a web
dashboard.

## Status

**Pre-alpha, phase 0: foundation.** Nothing runs yet. Phase 0 is a walking skeleton (the Maven
reactor, a server connected to its database and mail provider, the container image and the
delivery pipeline) built before any tunnel code. JBurrow is built in public, one capability at a
time; [`ROADMAP.md`](ROADMAP.md) lists the features and [`PROJECT.md`](PROJECT.md) §Build roadmap
gives the order.

## Quick start

You need JDK 21 and Docker. Maven comes with the repository (`./mvnw`).

```bash
make up                                   # Postgres on :5432, Mailpit on :1025 (inbox UI :8025)
make run                                  # the server, local profile: API :7090, management :7091
curl -s localhost:7090/api/v1/meta        # the running instance: id, version, commit, start time
curl -s localhost:7091/actuator/health    # health, including the database
make verify                               # unit, architecture and integration tests
```

`make help` lists every target. `make down` stops the local dependencies, and `make reset` also
deletes their data. Only the server runs today; tunnels arrive from phase 1.

## Documentation

- [`PROJECT.md`](PROJECT.md): the project brief, architecture, tech stack and module layout.
- [`ROADMAP.md`](ROADMAP.md): the feature list, staged as MVP, v1 and Later.
- [`docs/phase-0-foundation/ARCHITECTURE.md`](docs/phase-0-foundation/ARCHITECTURE.md): the
  detailed design of the current phase.
- [`docs/protocol.md`](docs/protocol.md): the wire protocol specification, for anyone writing a
  client or a server.
- [`docs/adr/`](docs/adr/): architecture decision records.
- [`CONTRIBUTING.md`](CONTRIBUTING.md): how the build fits together and how to send a change.
- [`SECURITY.md`](SECURITY.md): reporting a vulnerability.

## License

[Apache License 2.0](LICENSE). See [`NOTICE`](NOTICE).
