package io.jburrow.protocol.adapter.jackson;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import io.jburrow.protocol.control.ControlMessage;
import io.jburrow.protocol.control.UpgradeRefusal;
import io.jburrow.protocol.wire.ErrorCode;
import io.jburrow.protocol.wire.FrameLimits;
import io.jburrow.protocol.wire.ProtocolException;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.TokenStreamLocation;
import tools.jackson.core.exc.InputCoercionException;
import tools.jackson.core.exc.StreamConstraintsException;
import tools.jackson.core.exc.StreamReadException;
import tools.jackson.core.exc.UnexpectedEndOfInputException;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.cfg.EnumFeature;
import tools.jackson.databind.exc.MismatchedInputException;
import tools.jackson.databind.json.JsonMapper;

/**
 * The one JSON mapper for control payloads, enforcing {@code docs/protocol.md} §7.1 and nothing looser. Built once,
 * immutable and thread-safe.
 *
 * <p>Every reading feature is set explicitly, whatever Jackson's default, so an upgrade can't loosen the parser
 * silently. Leaf values are read by {@link WireValueModule}, which is strict on its own; the features stay set as a
 * second line for any type that module doesn't cover.
 *
 * <p>A rejected payload is a {@link ProtocolException}: {@link ErrorCode#PROTOCOL_ERROR} for anything that isn't
 * well-formed JSON of the right shape and types, {@link ErrorCode#VALIDATION_FAILED} for a value that breaks a field
 * rule. Messages carry a field path, a short reason and a line and column, never any part of the payload.
 */
public final class ControlJson {

    static final int MAX_NESTING_DEPTH = 8;
    static final int MAX_STRING_LENGTH = 4_096;
    static final int MAX_NAME_LENGTH = 64;
    static final int MAX_NUMBER_LENGTH = 20;
    static final int MAX_DOCUMENT_LENGTH = FrameLimits.MAX_CONTROL_PAYLOAD;

    private static final char BYTE_ORDER_MARK = '\uFEFF';

    private final JsonMapper mapper;

    private ControlJson(JsonMapper mapper) {
        this.mapper = mapper;
    }

    public static ControlJson create() {
        return new ControlJson(strictMapper());
    }

    /**
     * Parses one payload as {@code type}.
     *
     * @throws ProtocolException if the payload breaks an encoding rule or a field rule
     */
    public <T extends ControlMessage> T read(byte[] utf8, Class<T> type) {
        Objects.requireNonNull(utf8, "utf8");
        Objects.requireNonNull(type, "type");
        if (utf8.length > MAX_DOCUMENT_LENGTH) {
            throw protocolError("payload is longer than " + MAX_DOCUMENT_LENGTH + " bytes");
        }
        requireStrictUtf8(utf8);
        try {
            requireWellFormed(utf8);
            try (JsonParser parser = mapper.createParser(utf8)) {
                parser.nextToken();
                return mapper.readValue(parser, type);
            }
        } catch (JacksonException e) {
            throw translate(e);
        }
    }

    /**
     * Parses one payload read from {@code in}, which the codec backs with the frame's buffer. Reads at most one byte
     * past the payload limit.
     *
     * @throws ProtocolException if the payload breaks an encoding rule or a field rule
     */
    public <T extends ControlMessage> T read(InputStream in, Class<T> type) {
        Objects.requireNonNull(in, "in");
        byte[] payload;
        try {
            payload = in.readNBytes(MAX_DOCUMENT_LENGTH + 1);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return read(payload, type);
    }

    /**
     * Encodes {@code message} as compact UTF-8 JSON, omitting absent optional fields.
     *
     * @throws IllegalStateException if the encoding exceeds the control payload limit, which no peer would accept
     */
    public byte[] write(ControlMessage message) {
        byte[] json = mapper.writeValueAsBytes(Objects.requireNonNull(message, "message"));
        if (json.length > MAX_DOCUMENT_LENGTH) {
            throw new IllegalStateException(message.getClass().getSimpleName() + " encodes to " + json.length
                    + " bytes, over the " + MAX_DOCUMENT_LENGTH + "-byte control payload limit");
        }
        return json;
    }

    /** Encodes the body of a refused upgrade as compact UTF-8 JSON, omitting {@code supported} when it is absent. */
    public byte[] writeRefusal(UpgradeRefusal refusal) {
        return mapper.writeValueAsBytes(Objects.requireNonNull(refusal, "refusal"));
    }

    /**
     * Parses the body of a refused upgrade. The body is not a control payload, so only the mapper's own limits apply,
     * not the UTF-8 and syntax checks of {@link #read}. Unknown keys are ignored.
     *
     * @throws ProtocolException if the body is not a readable refusal; a client treats that as a failure to connect
     */
    public UpgradeRefusal readRefusal(byte[] utf8) {
        Objects.requireNonNull(utf8, "utf8");
        try {
            return mapper.readValue(utf8, UpgradeRefusal.class);
        } catch (JacksonException e) {
            throw translate(e);
        }
    }

    JsonMapper mapper() {
        return mapper;
    }

    private static JsonMapper strictMapper() {
        StreamReadConstraints limits = StreamReadConstraints.builder()
                .maxNestingDepth(MAX_NESTING_DEPTH)
                .maxStringLength(MAX_STRING_LENGTH)
                .maxNameLength(MAX_NAME_LENGTH)
                .maxNumberLength(MAX_NUMBER_LENGTH)
                .maxDocumentLength(MAX_DOCUMENT_LENGTH)
                .build();
        JsonFactory factory =
                JsonFactory.builder().streamReadConstraints(limits).build();
        return JsonMapper.builder(factory)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .disable(StreamReadFeature.INCLUDE_SOURCE_IN_LOCATION)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                .disable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
                .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
                .disable(DeserializationFeature.ACCEPT_SINGLE_VALUE_AS_ARRAY)
                .disable(DeserializationFeature.UNWRAP_SINGLE_VALUE_ARRAYS)
                .disable(DeserializationFeature.ACCEPT_EMPTY_STRING_AS_NULL_OBJECT)
                .disable(DeserializationFeature.ACCEPT_EMPTY_ARRAY_AS_NULL_OBJECT)
                .enable(DeserializationFeature.WRAP_EXCEPTIONS)
                .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
                .disable(MapperFeature.ACCEPT_CASE_INSENSITIVE_PROPERTIES)
                .disable(MapperFeature.ACCEPT_CASE_INSENSITIVE_ENUMS)
                .disable(MapperFeature.ACCEPT_CASE_INSENSITIVE_VALUES)
                .enable(MapperFeature.INFER_RECORD_GETTERS_FROM_COMPONENTS_ONLY)
                .enable(EnumFeature.FAIL_ON_NUMBERS_FOR_ENUMS)
                .disable(SerializationFeature.INDENT_OUTPUT)
                .changeDefaultPropertyInclusion(
                        inclusion -> JsonInclude.Value.construct(JsonInclude.Include.NON_NULL, null))
                .changeDefaultNullHandling(nulls -> JsonSetter.Value.forContentNulls(Nulls.FAIL))
                .addModule(new WireValueModule())
                .build();
    }

    /**
     * Strict UTF-8, checked before Jackson sees the bytes. Jackson's byte parser accepts overlong sequences, skips a
     * byte order mark, and switches to UTF-16 or UTF-32 when it finds a zero byte near the start. JSON never contains a
     * raw U+0000, so refusing zero bytes leaves it nothing to detect. The byte parser stays, rather than parsing the
     * decoded characters, because only it applies the string length limit to every string it reads.
     */
    private static void requireStrictUtf8(byte[] utf8) {
        CharBuffer chars;
        try {
            chars = StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(utf8));
        } catch (CharacterCodingException e) {
            throw protocolError("payload is not valid UTF-8");
        }
        if (chars.hasRemaining() && chars.get(0) == BYTE_ORDER_MARK) {
            throw protocolError("payload starts with a byte order mark");
        }
        for (byte b : utf8) {
            if (b == 0) {
                throw protocolError("payload contains a NUL byte");
            }
        }
    }

    /**
     * Reads every token of the payload once, before any field is bound, so the syntax rules and parser limits hold for
     * the whole payload, ignored values included, and always win over a field rule.
     *
     * <p>Every string is materialized because Jackson checks a string's length exactly only then. A string it skips,
     * such as the value of an unknown key, is checked only when its text buffer has to grow, and the buffer is recycled
     * between payloads: after one long string, a later payload could pass an over-long string through an ignored key.
     */
    private void requireWellFormed(byte[] utf8) {
        try (JsonParser parser = mapper.createParser(utf8)) {
            if (parser.nextToken() != JsonToken.START_OBJECT) {
                throw protocolError("payload is not a JSON object");
            }
            JsonToken token;
            while ((token = parser.nextToken()) != JsonToken.END_OBJECT
                    || !parser.streamReadContext().inRoot()) {
                if (token == null) {
                    throw protocolError("payload ends inside the JSON object");
                }
                if (token == JsonToken.VALUE_STRING) {
                    parser.getString();
                }
            }
            if (parser.nextToken() != null) {
                throw protocolError("payload has content after the JSON object");
            }
        }
    }

    private static ProtocolException translate(JacksonException e) {
        String path = path(e);
        ProtocolException rejected = rejectedByRecord(e);
        if (rejected != null) {
            if (path.isEmpty()) {
                return rejected;
            }
            String message = rejected.getMessage();
            return ProtocolException.session(rejected.code(), path + (message.startsWith("[") ? "" : ".") + message);
        }
        if (e instanceof WireValueModule.InvalidValueException invalid) {
            return ProtocolException.session(ErrorCode.VALIDATION_FAILED, path + " " + invalid.rule());
        }
        return protocolError(reason(e, path) + location(e));
    }

    /**
     * A record constructor's own error, which Jackson wraps; it already names the field within that record, or an
     * index within it for a record written as an array.
     */
    private static ProtocolException rejectedByRecord(Throwable e) {
        for (Throwable cause = e.getCause(); cause != null; cause = cause.getCause()) {
            if (cause instanceof ProtocolException rejected) {
                return rejected;
            }
        }
        return null;
    }

    /**
     * The reason for a {@code PROTOCOL_ERROR}. Jackson's own messages quote the payload, so none is used. The path is
     * only shown for values bound to a known field, whose names come from the records, not the payload.
     */
    private static String reason(JacksonException e, String path) {
        if (e instanceof StreamConstraintsException) {
            return "payload exceeds a JSON limit (nesting " + MAX_NESTING_DEPTH + ", string " + MAX_STRING_LENGTH
                    + ", key " + MAX_NAME_LENGTH + ", number " + MAX_NUMBER_LENGTH + " digits)";
        }
        if (e instanceof InputCoercionException && !path.isEmpty()) {
            return path + " is out of range";
        }
        if (e instanceof UnexpectedEndOfInputException) {
            return "payload ends inside the JSON object";
        }
        if (e instanceof StreamReadException) {
            return "payload is not well-formed JSON or repeats a key";
        }
        if (e instanceof MismatchedInputException && !path.isEmpty()) {
            return path + " has the wrong JSON type";
        }
        return "payload has unexpected content";
    }

    private static String path(JacksonException e) {
        StringBuilder path = new StringBuilder();
        for (JacksonException.Reference reference : e.getPath()) {
            if (reference.getPropertyName() != null) {
                if (!path.isEmpty()) {
                    path.append('.');
                }
                path.append(reference.getPropertyName());
            } else if (reference.getIndex() >= 0) {
                path.append('[').append(reference.getIndex()).append(']');
            }
        }
        return path.toString();
    }

    private static String location(JacksonException e) {
        TokenStreamLocation location = e.getLocation();
        if (location == null || location.getLineNr() < 1) {
            return "";
        }
        return " at line " + location.getLineNr() + ", column " + location.getColumnNr();
    }

    private static ProtocolException protocolError(String reason) {
        return ProtocolException.session(ErrorCode.PROTOCOL_ERROR, reason);
    }
}
