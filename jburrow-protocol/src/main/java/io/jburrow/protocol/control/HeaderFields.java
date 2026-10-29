package io.jburrow.protocol.control;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;

/**
 * The header fields of an HTTP head, in order, repeats kept ({@code docs/protocol.md} §7.10). Names are lower-case
 * tokens and values are ISO 8859-1 text, so a value's bytes survive the JSON as code points of the same number. No
 * field is joined with another, {@code set-cookie} above all.
 *
 * <p>Connection-specific fields never cross the tunnel: each side removes them, and the names its {@code connection}
 * field lists, before it builds a head.
 */
public record HeaderFields(List<Field> fields) {

    public static final int MAX_FIELDS = 256;

    public static final Set<String> CONNECTION_SPECIFIC =
            Set.of("connection", "keep-alive", "proxy-connection", "transfer-encoding", "te", "trailer", "upgrade");

    private static final String CONTENT_LENGTH = "content-length";

    /** One header field. On the wire, a two-element array {@code [name, value]}. */
    public record Field(String name, String value) {

        public Field {
            Fields.matching(name, Fields.HEADER_NAME, "name");
            Fields.headerValue(value, "value");
        }
    }

    public HeaderFields {
        int size = Fields.required(fields, "fields").size();
        if (size > MAX_FIELDS) {
            throw Fields.invalid("[" + MAX_FIELDS + "]", "is past the limit of " + MAX_FIELDS + " fields");
        }
        for (int i = 0; i < size; i++) {
            Field field = Fields.required(fields.get(i), "[" + i + "]");
            if (CONNECTION_SPECIFIC.contains(field.name())) {
                throw Fields.invalid("[" + i + "] name", "must not be a connection-specific field");
            }
        }
        fields = List.copyOf(fields);
    }

    /** Fields from alternating names and values: {@code of("host", "example.dev", "accept", "text/html")}. */
    public static HeaderFields of(String... namesAndValues) {
        if (namesAndValues.length % 2 != 0) {
            throw new IllegalArgumentException("Names and values must come in pairs");
        }
        List<Field> fields = new ArrayList<>(namesAndValues.length / 2);
        for (int i = 0; i < namesAndValues.length; i += 2) {
            fields.add(new Field(namesAndValues[i], namesAndValues[i + 1]));
        }
        return new HeaderFields(fields);
    }

    /** Every value of {@code name}, in order. */
    public List<String> all(String name) {
        return fields.stream()
                .filter(field -> field.name().equals(name))
                .map(Field::value)
                .toList();
    }

    /**
     * The value of {@code name}, which may appear at most once.
     *
     * @throws io.jburrow.protocol.wire.ProtocolException {@code VALIDATION_FAILED} if it appears more than once
     */
    public Optional<String> single(String name) {
        List<String> values = all(name);
        if (values.size() > 1) {
            throw Fields.invalid("headers", "must not repeat " + name);
        }
        return values.stream().findFirst();
    }

    /**
     * The {@code content-length}, if there is one: at most one field, of 1 to 16 decimal digits and at most
     * 2^53 - 1, the largest integer every JSON implementation reads exactly.
     *
     * @throws io.jburrow.protocol.wire.ProtocolException {@code VALIDATION_FAILED} if it is repeated or malformed
     */
    public OptionalLong contentLength() {
        Optional<String> value = single(CONTENT_LENGTH);
        if (value.isEmpty()) {
            return OptionalLong.empty();
        }
        String digits = value.get();
        if (digits.isEmpty() || digits.length() > Fields.MAX_CONTENT_LENGTH_DIGITS || !isDigits(digits)) {
            throw Fields.invalid("headers", "content-length must be 1 to 16 decimal digits");
        }
        long length = Long.parseLong(digits);
        if (length > Fields.MAX_CONTENT_LENGTH) {
            throw Fields.invalid("headers", "content-length must be at most 2^53 - 1");
        }
        return OptionalLong.of(length);
    }

    private static boolean isDigits(String text) {
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) < '0' || text.charAt(i) > '9') {
                return false;
            }
        }
        return true;
    }
}
