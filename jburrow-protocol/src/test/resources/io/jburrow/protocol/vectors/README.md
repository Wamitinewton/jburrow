# Conformance vectors for `jburrow.v1-draft.2`

These files test a frame decoder against the JBurrow wire protocol, `jburrow.v1-draft.2`, specified in
[`docs/protocol.md`](../../../../../../../../docs/protocol.md). Each vector is one binary WebSocket message and what a receiver must
make of it: a decoded frame, or an error code. They cover steps 1 to 8 of the specification's
[receiver rules](../../../../../../../../docs/protocol.md#13-receiver-rules), the checks that depend only on the bytes. Step 9
(whether the frame is allowed in the session's and the stream's state) is a session's job, and no
vector tests it. An implementation conforms to the framing layer when it passes every vector for
each role it implements ([§15](../../../../../../../../docs/protocol.md#15-conformance)).

JBurrow's own codec runs every vector in `ConformanceVectorsTest`. A third-party implementation
can run the same files. Any bug found by fuzzing or in production becomes a new vector.

## Files

| File | Each entry |
|---|---|
| `valid.json` | `{ "name", "role", "hex", "frame" }`: the message decodes to `frame` |
| `invalid.json` | `{ "name", "role", "hex", "error" }`: decoding fails with the error code `error` |

- **`name`** is unique across both files and stable, so a failure can be reported by name.
- **`role`** is the side that *receives* the message: `"server"` decodes what a client sends,
  `"client"` decodes what a server sends. It matters for types whose payload differs by direction
  (`HELLO`, `REGISTER`) and for types only one side may send (`OPEN`, `HEADERS`, `GOAWAY`).
- **`error`** is the protocol error code, for example `FRAME_SIZE_ERROR`. When several rules are
  broken, the expected code is the first broken step in receiver-rule order. Several vectors exist
  only to check that order.

## The `hex` format

The whole WebSocket message, as hexadecimal, in whitespace-separated tokens:

- A token of hex digit pairs is those bytes, in order.
- A token `<hex>*<count>` is the bytes of `<hex>` repeated `<count>` times in decimal. It keeps
  the 64 KiB and 16 KiB vectors short.
- Whitespace carries no meaning. The generator splits the 10-byte header into its fields
  (stream id, type, flags, length) to make it readable.

```
00000007 05 00 00000005 68656c6c6f     DATA on stream 7, payload "hello"
00000001 05 00 00010000 ab*65536       DATA on stream 1, 65,536 bytes of 0xab
```

An empty `hex` is an empty message.

## The `frame` format

A neutral description of the decoded frame. Every frame has `type` (the registry name) and
`streamId`. Other fields depend on the type:

| Type | Fields |
|---|---|
| `HELLO`, `REGISTER`, `OPEN`, `ERROR` | `payload`: the control payload as the receiver understands it, a JSON object. Compare it as JSON, not as text: key order and whitespace don't matter, and keys the receiver must ignore are absent. |
| `DATA` | `payloadHex`: the payload, in the same `hex` format |
| `WINDOW_UPDATE` | `increment`: the credit, a number |
| `CLOSE` | `reset`: the error code, present only for a reset (`RST` flag). Absent means a half-close. |
| `PING`, `PONG` | `opaqueHex`: the 8 opaque bytes |
| `HEADERS`, `GOAWAY` | nothing else. Their payloads belong to later drafts; a receiver of this draft decodes the header and then treats the frame as a session error. |

A vector holds for one draft only: drafts are not compatible, and a `HELLO` of one is not a `HELLO`
of another. These files change with the draft, and `docs/protocol.md` §1 lists what each draft
changed.
