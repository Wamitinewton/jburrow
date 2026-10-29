package io.jburrow.protocol.adapter.netty;

import static io.jburrow.protocol.adapter.netty.FrameCodec.Role.CLIENT;
import static io.jburrow.protocol.adapter.netty.FrameCodec.Role.SERVER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.argumentSet;

import io.jburrow.protocol.adapter.jackson.ControlJson;
import io.jburrow.protocol.adapter.netty.FrameCodec.Role;
import io.jburrow.protocol.control.ClientInfo;
import io.jburrow.protocol.control.ErrorBody;
import io.jburrow.protocol.control.Hello;
import io.jburrow.protocol.control.Open;
import io.jburrow.protocol.control.Register;
import io.jburrow.protocol.control.Registered;
import io.jburrow.protocol.control.RegisteredTunnel;
import io.jburrow.protocol.control.RemoteAddress;
import io.jburrow.protocol.control.ServerHello;
import io.jburrow.protocol.control.ServerInfo;
import io.jburrow.protocol.control.SessionLimits;
import io.jburrow.protocol.control.TunnelKind;
import io.jburrow.protocol.control.TunnelRequest;
import io.jburrow.protocol.control.Windows;
import io.jburrow.protocol.wire.ErrorCode;
import io.jburrow.protocol.wire.FrameLimits;
import io.jburrow.protocol.wire.FrameType;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.ByteBufUtil;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.json.JsonMapper;

/**
 * Every example in {@code docs/protocol.md} is the codec's output for a known frame, so the specification can't drift
 * from the reference implementation. An example sits between {@code <!-- example:<name> -->} and
 * {@code <!-- /example -->}: a {@code text} block with the message in hex, and for a control frame a {@code json}
 * block with its payload.
 */
class ProtocolDocumentExamplesTest {

    private static final Path DOCUMENT = Path.of("docs", "protocol.md");
    private static final Pattern EXAMPLE =
            Pattern.compile("<!-- example:([a-z0-9-]+) -->(.*?)<!-- /example -->", Pattern.DOTALL);
    private static final Pattern FENCE = Pattern.compile("```(\\w*)\\n(.*?)```", Pattern.DOTALL);
    private static final int TYPE_OFFSET = 4;
    private static final Set<FrameType> CONTROL_PAYLOADS =
            EnumSet.of(FrameType.HELLO, FrameType.REGISTER, FrameType.OPEN, FrameType.ERROR);
    private static final Pattern HEX_BYTE = Pattern.compile("[0-9a-f]{2}");

    private static final ByteBufAllocator ALLOC = ByteBufAllocator.DEFAULT;
    private static final ControlJson JSON = ControlJson.create();
    private static final JsonMapper MAPPER = JsonMapper.shared();

    private static final UUID SESSION_ID = UUID.fromString("0b8e5c1e-7c55-4c46-9a3e-2f1d0c9e4b7a");
    private static final UUID TUNNEL_ID = UUID.fromString("5d0f4f0e-31a6-4a39-8d51-0c0f7f1f6c23");

    /** What each example in the document must be, and the side that sends it. Data frames are built per use. */
    private static final Map<String, Expected> EXPECTED = expected();

    private record Expected(Role sender, Supplier<Frame> frame) {}

    private record Example(String name, byte[] message, Optional<String> json) {}

    private static Map<String, Expected> expected() {
        Map<String, Expected> examples = new LinkedHashMap<>();
        examples.put("data", new Expected(SERVER, () -> new Frame.Data(1, ascii("SSH-2.0-OpenSSH_9.6\r\n"))));
        examples.put("window-update", new Expected(CLIENT, () -> new Frame.WindowUpdate(1, 131_072)));
        examples.put("window-update-session", new Expected(SERVER, () -> new Frame.WindowUpdate(0, 262_144)));
        examples.put("close-half", new Expected(CLIENT, () -> Frame.Close.half(1)));
        examples.put(
                "close-reset", new Expected(CLIENT, () -> Frame.Close.reset(1, ErrorCode.LOCAL_SERVICE_UNREACHABLE)));
        examples.put("ping", new Expected(SERVER, () -> new Frame.Ping(1)));
        examples.put("pong", new Expected(CLIENT, () -> new Frame.Pong(1)));
        examples.put(
                "hello-client",
                new Expected(
                        CLIENT,
                        () -> new Frame.Hello(new Hello(
                                new ClientInfo("jburrow-cli", "0.2.0", "linux", "amd64"),
                                List.of(),
                                new Windows(262_144, 1_048_576)))));
        examples.put(
                "hello-server",
                new Expected(
                        SERVER,
                        () -> new Frame.ServerHello(new ServerHello(
                                SESSION_ID,
                                new ServerInfo("jburrow-server", "0.2.0"),
                                List.of(),
                                new SessionLimits(65_536, 1, 1, 15_000, 45_000),
                                new Windows(262_144, 524_288)))));
        examples.put(
                "register-request",
                new Expected(
                        CLIENT,
                        () -> new Frame.Register(new Register(
                                "jb_LOCAL-DEVELOPMENT-ONLY-unlocks-localhost-00",
                                List.of(new TunnelRequest("default", TunnelKind.TCP, 20_417))))));
        examples.put(
                "register-reply",
                new Expected(
                        SERVER,
                        () -> new Frame.Registered(new Registered(List.of(RegisteredTunnel.of(
                                "default", TUNNEL_ID, TunnelKind.TCP, "tcp.example.dev", 20_417))))));
        examples.put(
                "open",
                new Expected(
                        SERVER,
                        () -> new Frame.Open(
                                1, new Open(TUNNEL_ID, TunnelKind.TCP, new RemoteAddress("203.0.113.9", 51_234)))));
        examples.put(
                "error",
                new Expected(
                        SERVER,
                        () -> new Frame.Error(new ErrorBody(
                                ErrorCode.PORT_UNAVAILABLE, "Port 20417 is already in use.", "default"))));
        return Map.copyOf(examples);
    }

    static Stream<Arguments> examples() {
        return parse(document()).stream().map(example -> argumentSet(example.name(), example));
    }

    @Test
    void documentHasEveryExpectedExampleOnce() {
        List<String> names = parse(document()).stream().map(Example::name).toList();

        assertThat(names).doesNotHaveDuplicates().containsExactlyInAnyOrderElementsOf(EXPECTED.keySet());
    }

    @ParameterizedTest
    @MethodSource("examples")
    void hexIsWhatTheCodecEncodes(Example example) {
        Expected expected = EXPECTED.get(example.name());

        ByteBuf encoded = codec(expected.sender()).encode(expected.frame().get(), ALLOC);
        try {
            assertThat(ByteBufUtil.hexDump(encoded)).isEqualTo(HexFormat.of().formatHex(example.message()));
        } finally {
            encoded.release();
        }
    }

    @ParameterizedTest
    @MethodSource("examples")
    void hexDecodesToTheSameFrame(Example example) {
        Expected expected = EXPECTED.get(example.name());
        ByteBuf message = ALLOC.buffer(example.message().length).writeBytes(example.message());

        Frame decoded;
        try {
            decoded = codec(receiverOf(expected.sender())).decode(message);
        } finally {
            message.release();
        }
        ByteBuf reencoded = codec(expected.sender()).encode(decoded, ALLOC);
        try {
            assertThat(ByteBufUtil.getBytes(reencoded)).isEqualTo(example.message());
        } finally {
            reencoded.release();
        }
    }

    /** A control frame shows its payload as JSON; the JSON must be what the hex carries, whatever its layout. */
    @ParameterizedTest
    @MethodSource("examples")
    void jsonIsThePayloadOfTheHex(Example example) {
        byte[] message = example.message();
        FrameType type = FrameType.fromCode(message[TYPE_OFFSET]).orElseThrow();
        boolean control = CONTROL_PAYLOADS.contains(type);

        assertThat(example.json().isPresent())
                .as("a json block exactly for a control frame")
                .isEqualTo(control);
        example.json().ifPresent(json -> {
            byte[] payload = Arrays.copyOfRange(message, FrameLimits.HEADER_LENGTH, message.length);
            assertThat(MAPPER.readTree(json)).isEqualTo(MAPPER.readTree(payload));
        });
    }

    private static List<Example> parse(String document) {
        Matcher block = EXAMPLE.matcher(document);
        List<Example> examples = new ArrayList<>();
        while (block.find()) {
            String name = block.group(1);
            String hex = null;
            String json = null;
            Matcher fence = FENCE.matcher(block.group(2));
            while (fence.find()) {
                switch (fence.group(1)) {
                    case "text" -> {
                        assertThat(hex).as("one hex block in example %s", name).isNull();
                        hex = fence.group(2);
                    }
                    case "json" -> {
                        assertThat(json)
                                .as("one json block in example %s", name)
                                .isNull();
                        json = fence.group(2);
                    }
                    default -> throw new AssertionError("Example " + name + " has a ```" + fence.group(1) + " block");
                }
            }
            assertThat(hex).as("hex block in example %s", name).isNotNull();
            examples.add(new Example(name, bytes(name, hex), Optional.ofNullable(json)));
        }
        return examples;
    }

    /** The notation of the document's §2.2: two lower-case hex digits per byte, separated by whitespace. */
    private static byte[] bytes(String name, String hex) {
        String[] tokens = hex.strip().split("\\s+");
        for (String token : tokens) {
            assertThat(token).as("byte in example %s", name).matches(HEX_BYTE.pattern());
        }
        return HexFormat.of().parseHex(String.join("", tokens));
    }

    private static String document() {
        Path start = Path.of("").toAbsolutePath();
        for (Path dir = start; dir != null; dir = dir.getParent()) {
            Path candidate = dir.resolve(DOCUMENT);
            if (Files.isRegularFile(candidate)) {
                try {
                    return Files.readString(candidate, StandardCharsets.UTF_8);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
        }
        throw new IllegalStateException("No " + DOCUMENT + " in " + start + " or above it");
    }

    private static FrameCodec codec(Role role) {
        return new FrameCodec(role, JSON);
    }

    private static Role receiverOf(Role sender) {
        return sender == SERVER ? CLIENT : SERVER;
    }

    private static ByteBuf ascii(String text) {
        byte[] bytes = text.getBytes(StandardCharsets.US_ASCII);
        return ALLOC.buffer(bytes.length).writeBytes(bytes);
    }
}
