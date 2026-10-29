/**
 * Peer liveness: when to send a {@code PING} and when to declare the peer dead. Pure Java with no clock or timer; the
 * caller passes {@code System.nanoTime()} readings in and acts on the decisions. The rules are
 * {@code docs/protocol.md} §11.
 */
package io.jburrow.protocol.liveness;
