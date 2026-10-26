/**
 * Flow control: per-stream and per-session credit windows, what this side may send, and the credit it owes its peer.
 * Pure Java with no I/O, clock or stream table, so the server and the client apply the same rules. Each call is a
 * decision and each return value is what to send; the caller carries it out.
 *
 * <p>{@link io.jburrow.protocol.flow.SessionFlow} is the entry point and the only way to change a credit or a window.
 * {@link io.jburrow.protocol.flow.SendCredit} and {@link io.jburrow.protocol.flow.ReceiveWindow} are read-only outside
 * this package, so an adapter can ask how much it may send but can't do its own arithmetic on credit.
 *
 * <p>Not thread-safe. Confined to the thread that owns its session. The normative rules are {@code docs/protocol.md}
 * §10 (flow control) from draft 2.
 */
package io.jburrow.protocol.flow;
