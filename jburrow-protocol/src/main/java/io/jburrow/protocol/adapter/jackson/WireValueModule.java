package io.jburrow.protocol.adapter.jackson;

import io.jburrow.protocol.control.BodyFraming;
import io.jburrow.protocol.control.HeaderFields;
import io.jburrow.protocol.control.TunnelKind;
import io.jburrow.protocol.wire.ErrorCode;
import io.jburrow.protocol.wire.ProtocolException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import tools.jackson.core.JsonGenerator;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.BeanDescription;
import tools.jackson.databind.DeserializationConfig;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.deser.ValueDeserializerModifier;
import tools.jackson.databind.deser.std.DelegatingDeserializer;
import tools.jackson.databind.deser.std.StdDeserializer;
import tools.jackson.databind.exc.MismatchedInputException;
import tools.jackson.databind.module.SimpleModule;
import tools.jackson.databind.ser.std.StdSerializer;
import tools.jackson.databind.type.CollectionType;
import tools.jackson.databind.util.AccessPattern;

/**
 * Strict readers for every leaf value a control payload holds, and the wire names of its enums.
 *
 * <p>Each reader accepts exactly one JSON token type and never coerces, whatever the mapper's coercion settings say:
 * Jackson's own {@code String} reader turns {@code 5} into {@code "5"} even with scalar coercion off. Each also tells a
 * missing field from an explicit {@code null}. A missing field reaches the record constructor (reference types) or is
 * reported as required (primitives), both {@code VALIDATION_FAILED}; a {@code null} is a wrong JSON type, so
 * {@code PROTOCOL_ERROR}. Optional fields are omitted on the wire, never {@code null}. Records and lists get the same
 * null rule by wrapping Jackson's readers for them.
 */
final class WireValueModule extends SimpleModule {

    private static final Pattern CANONICAL_UUID =
            Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

    @SuppressWarnings("unchecked")
    WireValueModule() {
        super("jburrow-wire-values");
        addDeserializer(String.class, new StringReader());
        addDeserializer(Integer.class, new IntReader(Integer.class));
        addDeserializer((Class<Integer>) (Class<?>) int.class, new IntReader((Class<Integer>) (Class<?>) int.class));
        addDeserializer(Long.class, new LongReader(Long.class));
        addDeserializer((Class<Long>) (Class<?>) long.class, new LongReader((Class<Long>) (Class<?>) long.class));
        addDeserializer(UUID.class, new UuidReader());
        addDeserializer(TunnelKind.class, new TunnelKindReader());
        addDeserializer(ErrorCode.class, new ErrorCodeReader());
        addDeserializer(BodyFraming.class, new BodyFramingReader());
        addDeserializer(HeaderFields.class, new HeaderFieldsReader());
        addSerializer(TunnelKind.class, new TunnelKindWriter());
        addSerializer(ErrorCode.class, new ErrorCodeWriter());
        addSerializer(BodyFraming.class, new BodyFramingWriter());
        addSerializer(HeaderFields.class, new HeaderFieldsWriter());
        setDeserializerModifier(new NullRejectingModifier());
    }

    /**
     * A value of the right JSON type that breaks a field rule: {@code VALIDATION_FAILED}. {@link #rule()} is the
     * reason without the field, which the mapper takes from the exception's path; it never contains the value.
     */
    static final class InvalidValueException extends MismatchedInputException {

        private final String rule;

        InvalidValueException(JsonParser parser, Class<?> type, String rule) {
            super(parser, rule, type);
            this.rule = rule;
        }

        String rule() {
            return rule;
        }
    }

    private static MismatchedInputException nullNotAllowed(DeserializationContext context, Class<?> type) {
        return MismatchedInputException.from(context.getParser(), type, "null is not allowed");
    }

    private static final class NullRejectingModifier extends ValueDeserializerModifier {

        @Override
        public ValueDeserializer<?> modifyDeserializer(
                DeserializationConfig config, BeanDescription.Supplier description, ValueDeserializer<?> deserializer) {
            return deserializer instanceof StrictReader<?> ? deserializer : new NullRejecting(deserializer);
        }

        @Override
        public ValueDeserializer<?> modifyCollectionDeserializer(
                DeserializationConfig config,
                CollectionType type,
                BeanDescription.Supplier description,
                ValueDeserializer<?> deserializer) {
            return new NullRejecting(deserializer);
        }
    }

    /** Jackson's reader for a record or a list, except that an explicit {@code null} is refused. */
    private static final class NullRejecting extends DelegatingDeserializer {

        NullRejecting(ValueDeserializer<?> delegate) {
            super(delegate);
        }

        @Override
        protected ValueDeserializer<?> newDelegatingInstance(ValueDeserializer<?> delegate) {
            return new NullRejecting(delegate);
        }

        @Override
        public AccessPattern getNullAccessPattern() {
            return AccessPattern.DYNAMIC;
        }

        @Override
        public Object getNullValue(DeserializationContext context) {
            throw nullNotAllowed(context, handledType());
        }

        @Override
        public Object getAbsentValue(DeserializationContext context) {
            return null;
        }
    }

    private abstract static class StrictReader<T> extends StdDeserializer<T> {

        private final JsonToken token;

        StrictReader(Class<T> type, JsonToken token) {
            super(type);
            this.token = token;
        }

        @Override
        public T deserialize(JsonParser parser, DeserializationContext context) {
            if (!parser.hasToken(token)) {
                @SuppressWarnings("unchecked")
                T unreachable = (T) context.handleUnexpectedToken(handledType(), parser);
                return unreachable;
            }
            return read(parser);
        }

        abstract T read(JsonParser parser);

        @Override
        public T getNullValue(DeserializationContext context) {
            throw nullNotAllowed(context, handledType());
        }

        @Override
        public AccessPattern getNullAccessPattern() {
            return AccessPattern.DYNAMIC;
        }

        /** A missing reference value is null, for the record constructor to judge. Primitives can't be. */
        @Override
        public Object getAbsentValue(DeserializationContext context) {
            if (handledType().isPrimitive()) {
                throw new InvalidValueException(context.getParser(), handledType(), "is required");
            }
            return null;
        }
    }

    private static final class StringReader extends StrictReader<String> {
        StringReader() {
            super(String.class, JsonToken.VALUE_STRING);
        }

        @Override
        String read(JsonParser parser) {
            return parser.getString();
        }
    }

    /** Out-of-range values fail in the parser as a coercion error, so they are {@code PROTOCOL_ERROR}. */
    private static final class IntReader extends StrictReader<Integer> {
        IntReader(Class<Integer> type) {
            super(type, JsonToken.VALUE_NUMBER_INT);
        }

        @Override
        Integer read(JsonParser parser) {
            return parser.getIntValue();
        }
    }

    private static final class LongReader extends StrictReader<Long> {
        LongReader(Class<Long> type) {
            super(type, JsonToken.VALUE_NUMBER_INT);
        }

        @Override
        Long read(JsonParser parser) {
            return parser.getLongValue();
        }
    }

    /** Only the canonical lower-case form; Jackson's own reader also takes upper case and base64. */
    private static final class UuidReader extends StrictReader<UUID> {
        UuidReader() {
            super(UUID.class, JsonToken.VALUE_STRING);
        }

        @Override
        UUID read(JsonParser parser) {
            String text = parser.getString();
            if (!CANONICAL_UUID.matcher(text).matches()) {
                throw new InvalidValueException(parser, UUID.class, "must be a canonical lower-case UUID");
            }
            return UUID.fromString(text);
        }
    }

    private static final class TunnelKindReader extends StrictReader<TunnelKind> {
        private static final String RULE = "must be one of "
                + Arrays.stream(TunnelKind.values()).map(TunnelKind::wireName).toList();

        TunnelKindReader() {
            super(TunnelKind.class, JsonToken.VALUE_STRING);
        }

        @Override
        TunnelKind read(JsonParser parser) {
            return TunnelKind.fromWireName(parser.getString())
                    .orElseThrow(() -> new InvalidValueException(parser, TunnelKind.class, RULE));
        }
    }

    private static final class ErrorCodeReader extends StrictReader<ErrorCode> {
        ErrorCodeReader() {
            super(ErrorCode.class, JsonToken.VALUE_STRING);
        }

        @Override
        ErrorCode read(JsonParser parser) {
            return ErrorCode.fromCode(parser.getString())
                    .orElseThrow(
                            () -> new InvalidValueException(parser, ErrorCode.class, "must be a known error code"));
        }
    }

    private static final class BodyFramingReader extends StrictReader<BodyFraming> {
        private static final String RULE = "must be one of "
                + Arrays.stream(BodyFraming.values()).map(BodyFraming::wireName).toList();

        BodyFramingReader() {
            super(BodyFraming.class, JsonToken.VALUE_STRING);
        }

        @Override
        BodyFraming read(JsonParser parser) {
            return BodyFraming.fromWireName(parser.getString())
                    .orElseThrow(() -> new InvalidValueException(parser, BodyFraming.class, RULE));
        }
    }

    /**
     * Header fields as an array of {@code [name, value]} arrays. An entry of any other shape breaks a field rule rather
     * than an encoding rule, so it is {@code VALIDATION_FAILED}, named by its index. The record's own rules then apply
     * to each field and to the list.
     */
    private static final class HeaderFieldsReader extends StrictReader<HeaderFields> {
        HeaderFieldsReader() {
            super(HeaderFields.class, JsonToken.START_ARRAY);
        }

        @Override
        HeaderFields read(JsonParser parser) {
            List<HeaderFields.Field> fields = new ArrayList<>();
            for (int i = 0; isEntry(parser.nextToken()); i++) {
                String[] pair = readPair(parser);
                if (pair == null) {
                    throw invalid("[" + i + "] must be an array of two strings");
                }
                try {
                    fields.add(new HeaderFields.Field(pair[0], pair[1]));
                } catch (ProtocolException rejected) {
                    throw invalid("[" + i + "] " + rejected.getMessage());
                }
            }
            return new HeaderFields(fields);
        }

        /** {@code null} ends the loop too; the payload was checked to be well-formed before any field was read. */
        private static boolean isEntry(JsonToken token) {
            return token != JsonToken.END_ARRAY && token != null;
        }

        /** The entry at the parser as a name and a value, or {@code null} if it isn't two strings. Consumes it whole. */
        private static String[] readPair(JsonParser parser) {
            if (!parser.hasToken(JsonToken.START_ARRAY)) {
                parser.skipChildren();
                return null;
            }
            String[] pair = new String[2];
            int count = 0;
            boolean strings = true;
            for (JsonToken token = parser.nextToken(); isEntry(token); token = parser.nextToken()) {
                if (token == JsonToken.VALUE_STRING && count < pair.length) {
                    pair[count] = parser.getString();
                } else {
                    strings = false;
                    parser.skipChildren();
                }
                count++;
            }
            return strings && count == pair.length ? pair : null;
        }

        private static ProtocolException invalid(String message) {
            return ProtocolException.session(ErrorCode.VALIDATION_FAILED, message);
        }
    }

    private static final class TunnelKindWriter extends StdSerializer<TunnelKind> {
        TunnelKindWriter() {
            super(TunnelKind.class);
        }

        @Override
        public void serialize(TunnelKind kind, JsonGenerator generator, SerializationContext context) {
            generator.writeString(kind.wireName());
        }
    }

    private static final class ErrorCodeWriter extends StdSerializer<ErrorCode> {
        ErrorCodeWriter() {
            super(ErrorCode.class);
        }

        @Override
        public void serialize(ErrorCode code, JsonGenerator generator, SerializationContext context) {
            generator.writeString(code.code());
        }
    }

    private static final class BodyFramingWriter extends StdSerializer<BodyFraming> {
        BodyFramingWriter() {
            super(BodyFraming.class);
        }

        @Override
        public void serialize(BodyFraming framing, JsonGenerator generator, SerializationContext context) {
            generator.writeString(framing.wireName());
        }
    }

    private static final class HeaderFieldsWriter extends StdSerializer<HeaderFields> {
        HeaderFieldsWriter() {
            super(HeaderFields.class);
        }

        @Override
        public void serialize(HeaderFields headers, JsonGenerator generator, SerializationContext context) {
            generator.writeStartArray();
            for (HeaderFields.Field field : headers.fields()) {
                generator.writeStartArray();
                generator.writeString(field.name());
                generator.writeString(field.value());
                generator.writeEndArray();
            }
            generator.writeEndArray();
        }
    }
}
