package io.jburrow.protocol.adapter.netty;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.junit.jupiter.params.provider.Arguments.argumentSet;

import io.jburrow.protocol.adapter.jackson.ControlJson;
import io.jburrow.protocol.adapter.netty.FrameCodec.Role;
import io.jburrow.protocol.control.ControlMessage;
import io.jburrow.protocol.testsupport.ConformanceVectors;
import io.jburrow.protocol.testsupport.Hex;
import io.jburrow.protocol.wire.ErrorCode;
import io.jburrow.protocol.wire.ErrorScope;
import io.jburrow.protocol.wire.FrameType;
import io.jburrow.protocol.wire.ProtocolException;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.ByteBufUtil;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Runs every conformance vector in {@code io/jburrow/protocol/vectors} against the codec. */
class ConformanceVectorsTest {

    private static final JsonMapper MAPPER = JsonMapper.shared();
    private static final ControlJson JSON = ControlJson.create();
    private static final ByteBufAllocator ALLOC = ByteBufAllocator.DEFAULT;

    static Stream<Arguments> validVectors() {
        return ConformanceVectors.valid().stream()
                .map(vector -> argumentSet(vector.path("name").asString(), vector));
    }

    static Stream<Arguments> invalidVectors() {
        return ConformanceVectors.invalid().stream()
                .map(vector -> argumentSet(vector.path("name").asString(), vector));
    }

    @ParameterizedTest
    @MethodSource("validVectors")
    void decodesToTheExpectedFrame(JsonNode vector) {
        ByteBuf message = message(vector);
        Frame frame = receiver(vector).decode(message);
        try {
            assertThat(describe(frame)).isEqualTo(normalized(vector.path("frame")));
        } finally {
            frame.release();
            message.release();
        }
        assertThat(message.refCnt()).isZero();
    }

    /**
     * The encoder must produce the vector's exact bytes. Vectors padded with insignificant whitespace, or carrying
     * keys a receiver ignores, decode to the same frame as a shorter message, so they can't round-trip byte for byte.
     */
    @ParameterizedTest
    @MethodSource("validVectors")
    void encodesCanonicalVectorsToTheSameBytes(JsonNode vector) {
        ByteBuf message = message(vector);
        byte[] expected = ByteBufUtil.getBytes(message);
        Frame frame = receiver(vector).decode(message);
        message.release();
        if (frame instanceof Frame.Unsupported || !isCanonical(vector)) {
            frame.release();
            return;
        }

        ByteBuf encoded = sender(vector).encode(frame, ALLOC);
        try {
            assertThat(ByteBufUtil.hexDump(encoded)).isEqualTo(HexFormat.of().formatHex(expected));
        } finally {
            encoded.release();
        }
        assertThat(frame.refCnt()).isEqualTo(frame instanceof Frame.Data ? 0 : 1);
    }

    @ParameterizedTest
    @MethodSource("invalidVectors")
    void failsWithTheExpectedSessionError(JsonNode vector) {
        ByteBuf message = message(vector);
        ErrorCode expected = ErrorCode.valueOf(vector.path("error").asString());

        ProtocolException error = catchThrowableOfType(
                ProtocolException.class, () -> receiver(vector).decode(message).release());

        assertThat(error).as("decoding %s", vector.path("name").asString()).isNotNull();
        assertThat(error.code()).isEqualTo(expected);
        assertThat(error.scope()).isEqualTo(ErrorScope.SESSION);
        assertThat(message.refCnt()).isEqualTo(1);
        message.release();
    }

    @Test
    void namesAreUniqueAcrossBothFiles() {
        Set<String> names = new HashSet<>();
        for (JsonNode vector : ConformanceVectors.all()) {
            assertThat(names.add(vector.path("name").asString()))
                    .as("duplicate vector %s", vector.path("name").asString())
                    .isTrue();
        }
    }

    @Test
    void validVectorsCoverEveryFrameType() {
        Set<FrameType> covered = EnumSet.noneOf(FrameType.class);
        for (JsonNode vector : ConformanceVectors.valid()) {
            covered.add(FrameType.valueOf(vector.path("frame").path("type").asString()));
        }
        assertThat(covered).isEqualTo(EnumSet.allOf(FrameType.class));
    }

    @Test
    void invalidVectorsCoverEveryCodecError() {
        Set<String> errors = new HashSet<>();
        for (JsonNode vector : ConformanceVectors.invalid()) {
            errors.add(vector.path("error").asString());
        }
        assertThat(errors).containsExactlyInAnyOrder("FRAME_SIZE_ERROR", "PROTOCOL_ERROR", "VALIDATION_FAILED");
    }

    /** The decoded frame in the vectors' neutral form; see {@code vectors/README.md}. */
    private static JsonNode describe(Frame frame) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("type", frame.type().name());
        node.put("streamId", frame.streamId());
        switch (frame) {
            case Frame.Hello hello -> node.set("payload", payload(hello.message()));
            case Frame.ServerHello hello -> node.set("payload", payload(hello.message()));
            case Frame.Register register -> node.set("payload", payload(register.message()));
            case Frame.Registered registered -> node.set("payload", payload(registered.message()));
            case Frame.Open open -> node.set("payload", payload(open.message()));
            case Frame.Error error -> node.set("payload", payload(error.body()));
            case Frame.Data data -> node.put("payloadHex", ByteBufUtil.hexDump(data.payload()));
            case Frame.WindowUpdate update -> node.put("increment", update.increment());
            case Frame.Close close -> close.reset().ifPresent(code -> node.put("reset", code.code()));
            case Frame.Ping ping -> node.put("opaqueHex", HexFormat.of().toHexDigits(ping.opaque()));
            case Frame.Pong pong -> node.put("opaqueHex", HexFormat.of().toHexDigits(pong.opaque()));
            case Frame.Unsupported ignored -> {}
        }
        return node;
    }

    /** The expected frame with {@code payloadHex} expanded, since the vectors may write it in repeat notation. */
    private static JsonNode normalized(JsonNode expected) {
        ObjectNode copy = (ObjectNode) expected.deepCopy();
        if (copy.has("payloadHex")) {
            copy.put(
                    "payloadHex",
                    HexFormat.of().formatHex(Hex.decode(copy.path("payloadHex").asString())));
        }
        return copy;
    }

    private static JsonNode payload(ControlMessage message) {
        return MAPPER.readTree(JSON.write(message));
    }

    private static boolean isCanonical(JsonNode vector) {
        String name = vector.path("name").asString();
        boolean control = vector.path("frame").has("payload");
        return !(control && name.contains("-maximum")) && !name.contains("unknown-key");
    }

    private static FrameCodec receiver(JsonNode vector) {
        return new FrameCodec(role(vector), JSON);
    }

    private static FrameCodec sender(JsonNode vector) {
        return new FrameCodec(role(vector) == Role.SERVER ? Role.CLIENT : Role.SERVER, JSON);
    }

    private static Role role(JsonNode vector) {
        return Role.valueOf(vector.path("role").asString().toUpperCase(Locale.ROOT));
    }

    private static ByteBuf message(JsonNode vector) {
        byte[] bytes = Hex.decode(vector.path("hex").asString());
        return ALLOC.buffer(bytes.length).writeBytes(bytes);
    }
}
