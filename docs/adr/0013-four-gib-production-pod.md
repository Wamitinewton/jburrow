# 13. A 4 GiB production pod, sized for 1,000 sessions

- Status: accepted
- Date: 2026-10-30

## Context

ADR-0008 fixed the production pod at 1 GiB, with direct memory capped at 256 MiB, and ADR-0010 sized
the edge to fit it: 200 sessions, 4,000 streams and a 30% heap. ADR-0010 also said that a server
needing more raises `max-streams` together with the pod's memory.

The node the pod runs on has 23 GiB, and the limits of everything on it added up to 17.3 GiB. At 200
sessions the server turns away the 201st CLI with `SERVER_AT_CAPACITY`. A tunnel is one session in
this version, so 200 is also the most tunnels the server can hold at once. That is close to the number
of testers expected once HTTP tunnels ship, and the node can give JBurrow more memory without
overcommitting.

## Decision

The production pod is limited to 4 GiB, with a 1 GiB request, and the edge is sized to it:

| Setting | 1 GiB pod (the code's defaults) | 4 GiB pod (production) |
|---|---|---|
| `jburrow.edge.max-sessions` | 200 | 1,000 |
| `jburrow.edge.max-streams` | 4,000 | 8,000 |
| `jburrow.http.max-parked` | 16 MB | 64 MB |
| `-Dio.netty.maxDirectMemory` | 256 MiB | 1,280 MiB |
| `-XX:MaxRAMPercentage` | 30% (307 MiB) | 30% (1,229 MiB) |

The pod's worst case at these values:

| Part | Bytes |
|---|---|
| Heap, 30% of the pod | 1,229 MiB |
| Direct memory, capped. Budgeted: 1,000 sessions × 896 KiB + 64 handshakes × 64 KiB + 64 MiB parked = 943 MiB (74% of the cap) | 1,280 MiB |
| Socket buffers of held-back streams: 8,000 × 64 KiB (ADR-0010) | 500 MiB |
| The JVM's non-heap memory, estimated | about 300 MiB |
| **Total** | **about 3.2 GiB of 4 GiB** |

1,000 sessions matches the 1,000 ports of the production TCP range, so every session can hold a TCP
tunnel. `max-concurrent-streams` (1,000 per session) and `max-sessions-per-principal` (3) don't
change: they bound one client, not the server.

The code's defaults stay sized for the 1 GiB reference pod that self-hosters start from. The
deployment sets the production values next to the limit they depend on, so they change together.

## Consequences

- The server can hold five times the sessions and twice the streams. The startup budget line reads
  `1000 sessions × 896 KiB + 64 handshakes × 64 KiB + 64 MiB parked = 943 MiB of 1280 MiB (74%)`.
- The heap has room for what ADR-0010 moves onto it: a drained visitor's receive buffer, 64 KiB per
  stream, is 500 MiB at 8,000 streams, which 1,229 MiB covers.
- The sessions' own socket buffers are still outside the sum, as in ADR-0010. They are auto-tuned by
  the kernel, and the roughly 790 MiB the table leaves unused is their allowance. Measuring them
  under load is open.
- The 500 MiB of socket buffers also counts against the node's `tcp_mem`, which every workload on the
  node shares. Its pressure threshold, past which the kernel shrinks every TCP socket on the node, is
  about 1.5 GiB there, so JBurrow's worst case is about a third of it. Raising `max-streams` again
  means checking that threshold first.
- The CPU limit stays at one core. If it becomes the bottleneck, the next step is raising it, not
  adding a pod: the server runs as one pod (ADR-0008).
- The node's limits add up to about 20.3 GiB of 23 GiB once this applies.

## Alternatives considered

- **5 GiB.** The node has room for it, but 4 GiB already covers 1,000 sessions with about 790 MiB to
  spare, and every gigabyte promised to one workload is one the others can't burst into.
- **Raising the code's defaults.** Every self-hoster would then start from a budget that warns, or
  fails with `OutOfDirectMemoryError`, on a small VPS. Sizing belongs to the deployment.
- **A second pod.** The session registry, the route table and the TCP listeners are in one process's
  memory, and the pod binds its ports on the node (ADR-0008, PROJECT.md §Production deployment).
  Running several pods needs a shared session directory and forwarding between pods first.
