# 7. Wire protocol v1

- Status: accepted
- Date: 2026-10-19

## Context

The wire protocol is JBurrow's public contract. The server and the client may share nothing
else (ADR-0002), and roadmap section 1 promises that a third-party client can be written from the
specification alone. Every byte a tunnel carries passes through it, and it is the only code that
turns untrusted bytes from the internet into objects in the server's memory.

`PROJECT.md` §The wire protocol sketches the frame layout, the frame types and JSON control
payloads. Before any code exists, several things it leaves open have to be settled, because they
are expensive to change once a codec, conformance vectors, a fuzzer corpus and released CLIs
depend on them:

- **Evolution.** Phases 2 to 4 add flow control, HTTP tunnels, `GOAWAY` and resume. Each adds
  frames or fields, and none of them should need a new frame header.
- **Strictness.** A lenient receiver hides interoperability bugs and widens the attack surface
  of a public server. A strict one has to say exactly what it rejects and in which order.
- **Error codes.** `PROJECT.md` requires protocol and REST errors to share one code space, but the
  REST `ErrorCode` lives in `jburrow-server-api`, which a client can never see.
- **Where the protocol model sits in the layer rules.** ADR-0002 leaves open what happens when a
  core layer needs types from outside its own feature. The server's session and the client's
  session both reason in frame types, stream ids and error codes. Under the phase 0 rules, their
  `domain` and `application` layers could not use `io.jburrow.protocol` at all.

The complete design is `docs/phase-1-tcp-tunnel/ARCHITECTURE.md` §The wire protocol, and
`docs/protocol.md` once it is written. This record captures why it is shaped that way.

## Decision

- **WebSocket over TLS on 443, one protocol frame per binary WebSocket message.** It gets through
  networks that block unusual ports and through TLS-inspecting proxies, reuses the cluster's
  ingress and certificates, and Netty implements it well on both sides. Text messages are a
  protocol error. **No WebSocket extensions**: `permessage-deflate` would compress
  attacker-chosen bytes in the same context as the authtoken, the CRIME/BREACH pattern. **An
  upgrade carrying `Origin` is refused**, so no web page can turn a browser into a tunnel client.
- **A 10-byte frame header** (reserved bit, 31-bit stream id, 8-bit type, 8-bit flags, 32-bit
  length) **and a fixed type registry** `0x01`–`0x0B`. The types phases 2 to 4 need (`HEADERS`,
  `WINDOW_UPDATE`, `GOAWAY`) are assigned now, so the codec and the fuzzer cover the whole frame
  grammar from the first draft, and a later phase defines a payload rather than a header. The
  length field is redundant inside a WebSocket message but lets a raw TCP+TLS transport reuse the
  codec, and checking it against the message length catches framing bugs early.
- **The major version is the WebSocket subprotocol**: `jburrow.v1-draft.N` until v1.0.0, then
  `jburrow.v1`. A version mismatch fails the upgrade with a clear error before any frame is
  exchanged. **Within a version, change is additive and negotiated through capabilities in
  `HELLO`.** Unknown JSON fields are ignored, so an optional field needs no negotiation.
  Everything else unknown (frame types, flags, capabilities used without negotiation) is an
  error.
- **Strict receivers with a fixed validation order.** Every frame is checked in the same nine
  steps, and the first failure decides the error. The order guarantees the decoder never
  allocates or parses anything a bad header could make expensive.
- **JSON control payloads with fixed parser limits** (nesting, string, key and number lengths;
  duplicate keys rejected; nothing coerced). JSON keeps the protocol readable in a packet capture
  while it changes every phase. Protobuf is deferred: it can arrive later as a negotiated change,
  without a new frame layout.
- **One error code space, two enums.** Protocol codes are `io.jburrow.protocol.wire.ErrorCode`,
  with the scopes each code may appear in (upgrade, session, stream), its upgrade status and its
  advisory close code. REST codes stay in `jburrow-server-api`. `ErrorCodeSpaceTest` in
  `jburrow-server`, the one module that sees both, requires the names they share to be exactly a
  declared set (today `NOT_FOUND`, `VALIDATION_FAILED`, `INTERNAL_ERROR`). A new collision fails
  the build until someone decides whether it is the same error or a different one.
- **The protocol model is pure Java that core layers may depend on.** `io.jburrow.protocol`
  outside its `adapter` packages may depend only on the JDK and itself
  (`PROTOCOL_MODEL_IS_PURE`). In return, `DOMAIN_IS_PURE` and `APPLICATION_IS_PURE`, on both the
  server and the client, accept it as a dependency. Netty and Jackson stay in
  `io.jburrow.protocol.adapter.netty` and `adapter.jackson`, which core layers still can't use.
  This answers ADR-0002's open consequence for the protocol: the shared vocabulary is a module of
  its own with the same purity as a domain, not a second copy inside each core. The client rules
  also gain `NETTY_ONLY_IN_ADAPTERS`, and both architecture tests stop importing `*-tests.jar`
  so the protocol's Netty test support isn't checked as production code.

## Consequences

- The frame header is frozen. A new kind of message is a new type code, or a new payload for an
  assigned one, never a new header field.
- Every wire-incompatible change before v1.0.0 bumps the draft number and breaks old CLIs on
  purpose. Phases 2 to 4 each expect to bump it once. That is acceptable while nobody outside the
  maintainer runs the CLI; at v1.0.0 the token freezes and old clients keep working.
- The codec rejects more than a lenient one would, so interoperability bugs show up as clear,
  named errors at the first bad frame rather than as corrupted streams later.
- A third-party client can run the same conformance vectors as the reference codec, and every bug
  the fuzzer or production finds becomes a vector.
- Two error enums have to be kept in step by a test rather than by the compiler, and every new
  protocol code is a row in the architecture document (and `docs/protocol.md`) as well as a
  constant.
- Server and client domain code can name frame types and error codes directly. A change to the
  protocol model is therefore a change to both cores, which is accurate: it is the contract they
  share.

## Alternatives considered

- **HTTP/2 or gRPC streams as the transport.** The ingress speaks HTTP/1.1 to upstreams, many
  proxies handle HTTP/2 poorly, and phase 3's per-request routing for HTTP tunnels would sit inside
  HTTP/2's own framing.
- **yamux or SSH channels.** Proven multiplexers, but neither has a native Java implementation
  worth adopting, and neither has a version negotiation story that fits draft numbering.
- **QUIC.** UDP is blocked on many of the networks the product has to work on. Worth revisiting as
  an additional transport later.
- **The version in `HELLO`.** A mismatch would only be discovered after the upgrade, and a future
  raw transport needs a connection preface anyway, which is where the subprotocol token moves.
- **Ignoring unknown frame types, as HTTP/2 does.** That leniency exists because HTTP/2 extensions
  can be sent without negotiation. With capabilities in `HELLO`, an unknown frame is always a bug,
  and surfacing it is worth more than tolerating it.
- **Protobuf control payloads now.** Smaller and schema-checked, but much harder to debug while the
  protocol changes every phase.
- **One shared error enum in `jburrow-protocol`.** The REST API would then depend on protocol types
  for errors like `MAIL_DELIVERY_FAILED` that have nothing to do with the wire, and every REST
  error would become part of the protocol's public contract.
- **A protocol vocabulary copied into each core's `domain`.** Keeps the phase 0 rules unchanged,
  but duplicates the registry on both sides and needs mapping code at every boundary, for types
  that are already pure Java.
