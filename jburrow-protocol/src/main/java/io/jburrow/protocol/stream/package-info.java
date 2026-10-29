/**
 * The per-stream lifecycle both sides track: states, the events that move a stream between them, and what the caller
 * does with each event; and for an HTTP stream, the order and body length rules of its exchange. Pure Java with no
 * I/O, so the server and the client apply the same rules. The normative tables are {@code docs/protocol.md} §9.2 and
 * §9.7.
 */
package io.jburrow.protocol.stream;
