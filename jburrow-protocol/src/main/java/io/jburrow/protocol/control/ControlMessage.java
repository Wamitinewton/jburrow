package io.jburrow.protocol.control;

/**
 * A control payload: the JSON body of a {@code HELLO}, {@code REGISTER}, {@code OPEN}, {@code HEADERS}, {@code GOAWAY} or
 * {@code ERROR} frame.
 */
public sealed interface ControlMessage
        permits Hello, ServerHello, Register, Registered, Open, HttpResponseHead, GoAway, ErrorBody {}
