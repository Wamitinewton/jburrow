<h1 align="center">
  <img src="docs/assets/jburrow-logo.png" alt="JBurrow" width="560">
</h1>

An open-source, self-hostable tunneling server, in the style of ngrok, written in Java.

A developer runs `jburrow http 3000` and gets back a public HTTPS URL. Any request to that URL lands
on the JBurrow server, travels down a single long-lived connection the CLI opened earlier, and comes
out on `localhost:3000` on the developer's machine. The response goes back up the same path.
`jburrow tcp 22` does the same for raw TCP: the server allocates a public port and every connection
to it is piped to the local SSH daemon. Around the tunnel engine sit the parts of a usable service:
accounts, auth tokens, reserved subdomains, rate limits, abuse controls, metrics and a web
dashboard.

## Status

**Pre-alpha, phase 3 complete: HTTP and TCP tunnels.** `jburrow http <port>` serves a local web
server on a public HTTPS URL, and `jburrow tcp <port>` exposes a local TCP port on a public port of
the server, over the wire protocol in [`docs/protocol.md`](docs/protocol.md). One session carries up
to 1,000 connections at once under credit-based flow control. Automatic reconnect and resume across
server restarts (phase 4) and accounts (phase 5) come next. JBurrow is built in public, one capability at
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

A local web server gets an HTTPS URL of its own instead of a port:

```bash
JBURROW_AUTHTOKEN=<your token> java -jar jburrow-cli/target/jburrow-cli-*.jar http 3000
# Forwarding  https://quiet-otter-4f2a.cloudnativehub.app -> http://localhost:3000

curl https://quiet-otter-4f2a.cloudnativehub.app/   # from anywhere, with the URL printed above
```

`--subdomain myapp` asks for a name of your own, and `--host-header rewrite` helps development
servers that only answer `localhost`. WebSocket isn't passed through yet, so a page loads but its
hot reload doesn't.

The tunnel exposes the local service to the whole internet for as long as it runs: use key
authentication for SSH, and stop the tunnel with Ctrl-C when you're done. If the connection drops
or the server restarts, the CLI says so in one line, reconnects by itself and asks for the same URL
or port, a random name included; open connections are cut, but new ones work as soon as it prints
`reconnected`. Pass `--no-reconnect` to have it exit with code 4 instead, as a script may prefer.

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

An HTTP service gets a URL instead of a port. Locally, tunnel names are under `localhost`, which
browsers and curl resolve to this machine without any DNS setup:

```bash
python3 scripts/http-echo-server.py 3000 &  # an HTTP service to tunnel to
JBURROW_AUTHTOKEN=jb_LOCAL-DEVELOPMENT-ONLY-unlocks-localhost-00 \
  java -jar jburrow-cli/target/jburrow-cli-*.jar http 3000 --server ws://localhost:7080/v1/connect
# Forwarding  http://quiet-otter-4f2a.localhost:7080 -> http://localhost:3000
#
# 14:02:11  GET  /hello                 200       6 B     4 ms

curl http://quiet-otter-4f2a.localhost:7080/hello   # another terminal, with the URL printed above
```

`--subdomain myapp` asks for `myapp.localhost:7080` instead of a random name, and
`--host-header rewrite` sends the local service `localhost:3000` as its `Host`, for frameworks that
only answer their own name.

The tunnel survives a server restart. Stop the server with Ctrl-C and start it again with `make run`:
the server warns the CLI before it goes, keeps the name for its owner for two minutes, even across
the restart, and the CLI comes back with the same URL:

```
14:05:40  connection lost: the server is restarting; reconnecting in 0.8 s
14:05:41  still reconnecting: the server isn't reachable (Couldn't connect to the server: Connection refused: localhost/127.0.0.1:7080); next try in 1.0 s
14:05:47  reconnected   http://quiet-otter-4f2a.localhost:7080
```

`jburrow tcp 22` instead tunnels to your SSH daemon (`ssh -p 21037 localhost`). Add `--output json`
for one JSON event per line. Ctrl-C stops the tunnel with exit code 0; any other exit code says
what went wrong (`jburrow tcp --help`, and ARCHITECTURE.md §The CLI for the list); `jburrow http`
uses the same codes. Pass the token
through `JBURROW_AUTHTOKEN` rather than `--authtoken`: a command-line argument is visible to every
user of the machine.

## Documentation

- [`PROJECT.md`](PROJECT.md): the project brief, architecture, tech stack and module layout.
- [`ROADMAP.md`](ROADMAP.md): the feature list, staged as MVP, v1 and Later.
- [`docs/phase-3-http-tunnels/ARCHITECTURE.md`](docs/phase-3-http-tunnels/ARCHITECTURE.md): the
  detailed design of the latest phase, HTTP tunnels; the earlier phases' designs are beside it in
  [`docs/`](docs/) (phase 0 foundation, phase 1 TCP tunnels and the wire protocol, phase 2
  multiplexing and flow control).
- [`docs/protocol.md`](docs/protocol.md): the wire protocol specification, for anyone writing a
  client or a server.
- [`docs/adr/`](docs/adr/): architecture decision records.
- [`CONTRIBUTING.md`](CONTRIBUTING.md): how the build fits together and how to send a change.
- [`SECURITY.md`](SECURITY.md): reporting a vulnerability.

## License

[Apache License 2.0](LICENSE). See [`NOTICE`](NOTICE).
