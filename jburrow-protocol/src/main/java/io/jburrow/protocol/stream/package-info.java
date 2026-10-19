/**
 * The per-stream lifecycle both sides track: states, the events that move a stream between them, and what the caller
 * does with each event. Pure Java with no I/O, so the server and the client apply the same table. The normative table
 * is {@code docs/protocol.md} §9.2.
 */
package io.jburrow.protocol.stream;
