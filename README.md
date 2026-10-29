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

**Pre-alpha, phase 1 complete: TCP tunnels.** `jburrow tcp <port>` exposes a local TCP port on a
public port of the server, over the wire protocol in [`docs/protocol.md`](docs/protocol.md). Each
session carries one connection at a time; multiplexing and flow control (phase 2), HTTP tunnels
(phase 3) and automatic reconnect (phase 4) come next. JBurrow is built in public, one capability at
a time; [`ROADMAP.md`](ROADMAP.md) lists the features and [`PROJECT.md`](PROJECT.md) §Build roadmap
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
deletes their data.

## Try it

### Against the hosted server

The hosted server at `connect.cloudnativehub.app` is open to testers. Accounts arrive in phase 5;
until then, ask the maintainer for an authtoken. There are no release binaries yet, so build the
CLI from this repository (JDK 21; `make native` builds a native binary instead, with GraalVM for
JDK 21 as the JDK). Build it from phase 2 or later: the server refuses older CLIs with
`upgrade jburrow` and exit code 6.

```bash
./mvnw -q -pl jburrow-cli -am package -DskipTests
JBURROW_AUTHTOKEN=<your token> java -jar jburrow-cli/target/jburrow-cli-*.jar tcp 22
# Forwarding  tcp://tcp.cloudnativehub.app:20417 -> localhost:22

ssh -p 20417 you@tcp.cloudnativehub.app   # from anywhere, with the port printed above
```

The tunnel exposes the local service to the whole internet for as long as it runs: use key
authentication for SSH, and stop the tunnel with Ctrl-C when you're done. If the connection drops
or the server restarts, the CLI exits with code 4; run the command again, with
`--remote-port <port>` to ask for the same port.

### Against a server on your machine

With the server running (`make run`, above), expose a local echo service through a TCP tunnel. The
token is the development token from [`CONTRIBUTING.md`](CONTRIBUTING.md); it only unlocks a server on
your own machine.

```bash
./mvnw -q -pl jburrow-cli -am package -DskipTests
ncat -lk 9000 --exec /bin/cat &           # something to tunnel to
JBURROW_AUTHTOKEN=jb_LOCAL-DEVELOPMENT-ONLY-unlocks-localhost-00 \
  java -jar jburrow-cli/target/jburrow-cli-*.jar tcp 9000 --server ws://localhost:7080/v1/connect
# Forwarding  tcp://localhost:21037 -> localhost:9000

echo hello | ncat localhost 21037         # another terminal, with the port printed above
```

`jburrow tcp 22` instead tunnels to your SSH daemon (`ssh -p 21037 localhost`). Add `--output json`
for one JSON event per line. Ctrl-C stops the tunnel with exit code 0; any other exit code says
what went wrong (`jburrow tcp --help`, and ARCHITECTURE.md §The CLI for the list). Pass the token
through `JBURROW_AUTHTOKEN` rather than `--authtoken`: a command-line argument is visible to every
user of the machine.

## Documentation

- [`PROJECT.md`](PROJECT.md): the project brief, architecture, tech stack and module layout.
- [`ROADMAP.md`](ROADMAP.md): the feature list, staged as MVP, v1 and Later.
- [`docs/phase-1-tcp-tunnel/ARCHITECTURE.md`](docs/phase-1-tcp-tunnel/ARCHITECTURE.md): the
  detailed design of the latest phase, TCP tunnels and the wire protocol;
  [`docs/phase-0-foundation/ARCHITECTURE.md`](docs/phase-0-foundation/ARCHITECTURE.md) is the one
  before it.
- [`docs/protocol.md`](docs/protocol.md): the wire protocol specification, for anyone writing a
  client or a server.
- [`docs/adr/`](docs/adr/): architecture decision records.
- [`CONTRIBUTING.md`](CONTRIBUTING.md): how the build fits together and how to send a change.
- [`SECURITY.md`](SECURITY.md): reporting a vulnerability.

## License

[Apache License 2.0](LICENSE). See [`NOTICE`](NOTICE).
