/**
 * The JSON control payloads of {@code HELLO}, {@code REGISTER}, {@code OPEN}, {@code HEADERS} and {@code ERROR} as
 * records. Each record validates itself in its constructor, so none can exist in a state the protocol forbids, whether
 * it was parsed or built in code. Pure Java: the JSON mapping is
 * {@code io.jburrow.protocol.adapter.jackson.ControlJson}.
 *
 * <p>Validation failures are {@code VALIDATION_FAILED} session errors whose message names the field relative to the
 * record that rejected it; the mapper prefixes the path from the payload root.
 */
package io.jburrow.protocol.control;
