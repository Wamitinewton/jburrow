# 10. A floor on the read gate's share, and bounded visitor receive buffers

- Status: accepted
- Date: 2026-10-29

## Context

The phase 2 load test (`docs/phase-2-multiplexing/ARCHITECTURE.md` §Performance and §Memory budget)
left two findings that had to be settled before the production rollout. Both come from decisions in
ADR-0009 that held at ten streams and didn't at a thousand.

- **Small frames.** ADR-0009 resumes every visitor in a session's line when credit returns, and lets
  each take an equal share of it in its first read, so that the order the transport reads them in
  doesn't decide who is served. With 1,000 visitors in line, a 512 KiB grant is split into frames
  of about 500 bytes. Against ten visitors on the same 20 Mbit link this cost 48 times the packets,
  8 times the CPU on both sides, and 22% of the link's throughput.
- **Kernel memory.** A visitor without credit isn't read, so what it sends waits in its socket's
  receive buffer on the server. Each held-back visitor parked about 125 KB there, at the kernel's
  default 128 KiB `rcvbuf`. At ADR-0009's server-wide cap of 20,000 streams that is about 2.3 GiB,
  charged by cgroup v2 to a pod limited to 1 GiB, of which the JVM may take 75% (ADR-0008).

The first problem can't be fixed by a floor on the share alone. When the line is resumed, visitors
the credit doesn't cover are still read, and each gives up one byte to the carry (ADR-0009), which
goes out later as a one-byte `DATA` frame. A grant to N visitors then still costs about N reads,
N frames and 2N `epoll_ctl` calls, whatever the floor.

## Decision

- **A floor on the share.** A visitor resumed from the line may take `max(16 KiB, credit ÷ visitors
  in line)` in its first read, or all the credit if less than 16 KiB is left. Both read gates, the
  server's and the client's, follow the same rule.
- **Resume only as many as the credit covers.** Visitors in line are resumed in line order until
  what the resumed ones may send adds up to all the session can send. The rest stay paused, keep
  their place, and give up no byte. Turns still rotate: a visitor leaves the line when a read of its
  own is sent, and rejoins at the back. Fairness now holds across grants rather than within each one.
- **One read per turn.** A visitor resumed from the line that still has credit after its read goes
  to the back of the line anyway, if any visitor in line is still paused. Otherwise the transport's
  order of reads could let it take the credit meant for the visitors resumed with it. NIO did exactly
  that in `ReadGateIntegrationTest`'s fairness check, giving one of ten visitors 1.9 times its share.
  A visitor that is alone, or whose line holds only visitors already resumed, keeps reading.
- **A re-check for unused credit.** A resumed visitor may have less to send than its share, or
  nothing, and no event would then tell the gate. So after a partial resume, or a visitor giving up
  its turn, the gate looks again 1 ms later, on the session's event loop, and resumes the next paused visitors if credit is still
  unused. At most one re-check is pending per session.
- **A fixed visitor receive buffer.** `jburrow.tcp.visitor-receive-buffer` (default 32 KiB) is set as
  `SO_RCVBUF` on each tunnel's listening socket, so every visitor inherits it from the handshake on.
  Linux doubles it for its own bookkeeping, so a held-back visitor parks at most 64 KiB. `0` leaves
  the buffer to the kernel.
- **A lower server-wide stream cap.** `jburrow.edge.max-streams` drops from 20,000 to 4,000: at
  64 KiB each, 250 MiB of kernel memory. The startup log states that figure next to the direct memory
  budget.
- **Carried bytes on the heap.** What a visitor reads past its credit is copied onto an unpooled
  heap buffer and the read's direct buffer released at once. With most of a long line paused across
  grants, many visitors half-close while held back, and epoll drains each one's receive buffer
  (ADR-0009): 500 uploads in `SlowClientIntegrationTest` drained 27 MB into direct memory, against a
  4 MiB bound, before the copy, and 112 KiB after it. The bytes were already counted as kernel
  memory; they move to the heap instead of direct memory, whose cap is what makes a leak fail loudly.
- **A smaller heap.** The deployment holds the heap to 30% of the pod (`-XX:MaxRAMPercentage=30.0`,
  about 307 MiB) instead of 75%. The heap the server needs at 4,000 streams is about 47 MiB (36 MiB
  idle plus 2.8 KiB per stream, measured), and what it gives up is what kernel socket memory needs.

## Consequences

- A grant splits into frames of at least 16 KiB however many visitors wait: 32 frames for a 512 KiB
  grant instead of 1,000, with as few reads and pauses. `ReadGateIntegrationTest` checks it with 100
  visitors on a 64 KiB session window, and fails at the old equal share.
- A visitor at the back of a long line waits more grants for its turn. With 1,000 visitors and
  512 KiB grants, a full rotation takes about 31 grants instead of one. Every visitor still gets
  roughly the same bytes over time.
- Credit a resumed visitor leaves unused can sit for up to 1 ms. That costs throughput only when
  resumed visitors have less to send than their share, which isn't the bulk-upload case the floor is
  for.
- A visitor's upload is capped at about its usable receive buffer ÷ its round trip: roughly 0.6 MiB/s
  at 50 ms and 160 KiB/s at 200 ms, since the kernel stops tuning a fixed buffer. That is below what
  one stream's credit allows (1.25 MiB/s at 200 ms, ADR-0009), so a visitor uploading over a long
  path will notice it. Downloads to the visitor are unaffected, and so is phase 1's 0.55 MiB/s exit
  transfer, which the client's uplink limits. An operator who needs more sets the key higher or to
  `0`, and pays in kernel memory per held-back visitor.
- The buffer also bounds what epoll's drain of a visitor that sent `FIN` while held back can move
  onto the heap: 64 KiB per visitor, 250 MiB at 4,000 streams, which the heap's 260 MiB of slack has
  to cover in the worst case, with little to spare. The copy costs a pass over those bytes, off the
  steady path: only bytes that can't be sent are carried. The client is always on NIO, where no drain
  happens, so its carry stays at one byte and isn't copied.
- 4,000 streams is 200 sessions with 20 visitors each, or four sessions at their 1,000 cap. A
  server that needs more raises `max-streams` together with the pod's memory.
- The pod's worst case is now: heap 307 MiB, direct memory 256 MiB (179 MiB budgeted), visitor
  receive buffers 250 MiB, and the JVM's own non-heap memory. The sessions' own socket buffers are
  outside this sum, as they were in phase 1, and are the next thing to measure.
- The JVM flag is the deployment's to set; this repository records the figure.

## Alternatives considered

- **A floor while still resuming every visitor in line**, the candidate in the phase 2 open
  questions. Visitors past the credit each give up a byte that goes out as its own frame, so the
  number of frames per grant stays at about the number of visitors in line.
- **Merging a carried byte into the visitor's next read**, on top of that floor. It removes the
  one-byte frames but keeps a read and two `epoll_ctl` calls per visitor per grant. It also needs a
  timer for the visitor that had only that byte, an SSH keystroke for example, which this design
  needs anyway.
- **Resuming only as many as the credit covers with no re-check.** This is the stall the phase 2
  design resumed every visitor to avoid: credit left behind a resumed visitor with nothing to send would wait for a
  `WINDOW_UPDATE` that may never come.
- **`SO_RCVBUF` on each accepted visitor instead of the listener.** It costs a system call per accept,
  and the TCP window scale is agreed in the handshake from the listener's buffer.
- **Only a lower `max-streams`.** Fitting 125 KB per visitor into the same memory would mean a cap of
  about 2,000, half the streams, to keep the kernel's receive auto-tuning for visitor uploads. Few
  visitors upload fast enough to need it.
- **Only a smaller heap.** 2.3 GiB doesn't fit in 1 GiB whatever the heap gets.
- **Leaving drained bytes in direct memory.** It keeps the relay free of copies, but makes the
  direct memory a session can hold grow with its paused visitors, which the per-session budget
  exists to prevent. Netty's epoll transport offers no way to stop the drain.
