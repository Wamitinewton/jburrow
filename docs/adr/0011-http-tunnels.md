# 11. HTTP tunnels as a capability of draft 2, carrying parsed messages

- Status: accepted
- Date: 2026-10-29

## Context

Phase 3 adds HTTP tunnels: `jburrow http 3000` gives a public `https://<name>.<domain>` URL whose
requests reach a local web server (`docs/phase-3-http-tunnels/ARCHITECTURE.md`). Phases 1 and 2
built a tunnel for bytes, where a stream is a visitor's TCP connection and neither side looks
inside it. HTTP differs in three ways that the wire has to answer:

- **Requests, not connections.** The ingress keeps a pool of keep-alive connections to the server
  and reuses each for any host under the wildcard, so one upstream connection carries requests for
  different tunnels one after another (`PROJECT.md` §Routing per request, not per connection). A
  stream can't be a connection; the server has to route each request on its own.
- **Two HTTP/1.1 parsers.** The server parses requests from the ingress and the client parses
  responses from the local service. If either forwarded the bytes it parsed, the parser on the far
  side would get the last word on where a message ends, and the two could disagree: request
  smuggling, and response splitting the other way.
- **Phase 2 CLIs are in testers' hands.** Draft 2 went out with phase 2. ADR-0007 says a version
  grows within itself through capabilities negotiated in `HELLO`; a new draft refuses every older
  client at the upgrade.

`docs/protocol.md` §4.2 already lets a capability introduce "a frame type, flag, field, field value
or error code" that may not be sent in a session unless the server listed the capability. Everything
HTTP needs on the wire is one of those.

## Decision

- **A capability, not a draft.** HTTP tunnels are `http`, the first capability of
  `jburrow.v1-draft.2`. It introduces `HEADERS` (`0x04`, assigned since draft 1), the tunnel kind
  `"http"`, `tunnels[].subdomain` in `REGISTER`, `request` in `OPEN`, and the codes
  `SUBDOMAIN_UNAVAILABLE`, `SUBDOMAIN_FORBIDDEN` and `LOCAL_SERVICE_ERROR`. In a session without
  it, each is refused exactly as anything unknown is (§4.3), so a peer that doesn't offer `http`
  sees draft 2 unchanged. A client that wants an HTTP tunnel sends its `REGISTER` once the server's
  `HELLO` has listed `http`.
- **One stream per exchange.** A stream of an HTTP tunnel carries one request and its response. The
  server routes every request by its `Host`, and the stream belongs to whichever session holds that
  name.
- **Parsed messages, not HTTP/1.1 bytes.** The request head travels in `OPEN`, each response head
  in a `HEADERS`, and both bodies as `DATA` with their framing removed, each ended by `CLOSE`. A
  head's `body` (`"empty"`, `"length"`, `"chunked"`) tells the receiver how to frame the body again
  on its own connection, and the stream rules hold the `DATA` to it: a `"length"` body is exactly its
  `content-length`, at the first frame past it or a `CLOSE` short of it.
- **Heads as JSON, shaped for HTTP/2.** Header fields are an ordered array of `[name, value]`
  pairs, repeats kept, names in lower case, values ISO 8859-1 text so the bytes HTTP allows survive
  in UTF-8 JSON. Connection-specific fields (`connection`, `keep-alive`, `proxy-connection`,
  `transfer-encoding`, `te`, `trailer`, `upgrade`) can't appear in a head. Every rule is checked in
  the record's constructor on both sides, and a head that breaks one is `VALIDATION_FAILED`.
- **Reserved for later:** trailers (dropped; a chunked body ends with an empty trailer section) and
  `101 Switching Protocols` (refused, kept for the `Upgrade` passthrough of phase 8).
- **Names are the server's policy.** A requested subdomain follows one name rule on the wire. Which
  names are reserved, which terms are blocked, and how long a name is held for its owner after a
  session ends belong to the server, which refuses with the two new session codes.

## Consequences

- Phase 2 CLIs keep working through the deploy, and testers upgrade when they want HTTP tunnels.
  The server still speaks exactly one draft, as §4.1 requires before v1.0.0.
- Request smuggling can't cross the tunnel: neither side ever trusts the other's framing, and a
  head can't carry `transfer-encoding`. CR, LF and NUL can't occur in a field value, so a value
  can't split a message on the far side either.
- The client sees exactly one request head and one response head per stream, which is what the
  inspection API (v1) and the CLI's per-request lines need.
- A later HTTP/2 edge or local connection fits without a wire change: names are already lower case
  and each message is already a head and a body.
- Each side now parses and re-frames HTTP itself, and both have to agree on the rules.
  `HttpExchangeRules` in `jburrow-protocol` holds them once for both, `ScriptedPeer` fails a test at
  the first rule an implementation breaks, and `HttpExchangeFuzzTest` fuzzes them.
- A head costs JSON on the wire, typically under 1 KiB, and must fit 16,384 bytes. A request whose
  head doesn't fit gets a `431` at the edge, and a response whose head doesn't fit becomes a reset
  with `LOCAL_SERVICE_ERROR`. A field value is limited to 4,096 characters, the JSON string limit
  of §7.1.
- `subdomain` and `request` are now known fields of draft 2. A phase 2 peer never sends either, but
  a `"tcp"` entry that carries one is `VALIDATION_FAILED` rather than ignored.
- HTTP tunnels take one more round trip to set up, since the client registers after the server's
  `HELLO`. TCP tunnels don't.

## Alternatives considered

- **A new draft, `jburrow.v1-draft.3`.** It would refuse every phase 2 CLI at the upgrade for a
  change that is purely additive, and the server could only speak one draft at a time.
- **Streams carrying raw HTTP/1.1 bytes**, the way TCP streams do. Simplest on the server, but it
  forwards the visitor's framing to the local service, so a framing disagreement between the edge's
  parser and the local service's becomes request smuggling through the tunnel. The client also
  couldn't report exchanges without parsing a byte stream it didn't frame.
- **Header fields as a JSON object.** Shorter, but an object can't keep field order or repeated
  names, and `set-cookie` can't be joined with commas.
- **Binary heads, such as HPACK.** Smaller and faster to parse, but every control payload is JSON
  until the move to Protobuf that ADR-0007 leaves for a later, negotiated change, and a second
  payload encoding now would mean a second parser to fuzz.
- **Optimistic `REGISTER` for HTTP**, sent before the server's `HELLO` as TCP's is. It saves a round
  trip but sends what `http` introduces before the session has it, which §4.2 forbids, and a server
  without `http` would refuse the whole session where the client could have reported it cleanly.
