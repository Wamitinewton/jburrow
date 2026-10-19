package io.jburrow.protocol.adapter.jackson;

import static io.jburrow.protocol.wire.ErrorCode.PROTOCOL_ERROR;
import static io.jburrow.protocol.wire.ErrorCode.VALIDATION_FAILED;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.Nulls;
import io.jburrow.protocol.control.ClientInfo;
import io.jburrow.protocol.control.ControlMessage;
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
import io.jburrow.protocol.wire.ErrorCode;
import io.jburrow.protocol.wire.ErrorScope;
import io.jburrow.protocol.wire.ProtocolException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.cfg.EnumFeature;
import tools.jackson.databind.json.JsonMapper;

class ControlJsonTest {

    private static final ControlJson JSON = ControlJson.create();

    private static final String TOKEN = "jb_LOCAL-DEVELOPMENT-ONLY-unlocks-localhost-00";
    private static final UUID SESSION_ID = UUID.fromString("0b8e5c1e-7c55-4c46-9a3e-2f1d0c9e4b7a");
    private static final UUID TUNNEL_ID = UUID.fromString("5d0f4f0e-31a6-4a39-8d51-0c0f7f1f6c23");

    private static final Hello HELLO_RECORD =
            new Hello(new ClientInfo("jburrow-cli", "0.2.0", "linux", "amd64"), List.of());
    private static final ServerHello SERVER_HELLO_RECORD = new ServerHello(
            SESSION_ID,
            new ServerInfo("jburrow-server", "0.2.0"),
            List.of(),
            new SessionLimits(65_536, 1, 1, 15_000, 45_000));
    private static final Register REGISTER_RECORD =
            new Register(TOKEN, List.of(new TunnelRequest("default", TunnelKind.TCP, 20_417)));
    private static final Registered REGISTERED_RECORD = new Registered(
            List.of(RegisteredTunnel.of("default", TUNNEL_ID, TunnelKind.TCP, "tcp.cloudnativehub.app", 20_417)));
    private static final Open OPEN_RECORD =
            new Open(TUNNEL_ID, TunnelKind.TCP, new RemoteAddress("203.0.113.9", 51_234));
    private static final ErrorBody ERROR_RECORD =
            new ErrorBody(ErrorCode.PORT_UNAVAILABLE, "Port 20417 is already in use.", "default");

    private static final String HELLO =
            "{\"client\":{\"name\":\"jburrow-cli\",\"version\":\"0.2.0\",\"os\":\"linux\",\"arch\":\"amd64\"},"
                    + "\"capabilities\":[]}";
    private static final String SERVER_HELLO = "{\"sessionId\":\"0b8e5c1e-7c55-4c46-9a3e-2f1d0c9e4b7a\","
            + "\"server\":{\"name\":\"jburrow-server\",\"version\":\"0.2.0\"},\"capabilities\":[],"
            + "\"limits\":{\"maxFramePayload\":65536,\"maxTunnels\":1,\"maxConcurrentStreams\":1,"
            + "\"pingIntervalMs\":15000,\"idleTimeoutMs\":45000}}";
    private static final String REGISTER = "{\"authtoken\":\"" + TOKEN + "\","
            + "\"tunnels\":[{\"ref\":\"default\",\"kind\":\"tcp\",\"remotePort\":20417}]}";
    private static final String REGISTERED = "{\"tunnels\":[{\"ref\":\"default\","
            + "\"tunnelId\":\"5d0f4f0e-31a6-4a39-8d51-0c0f7f1f6c23\",\"kind\":\"tcp\","
            + "\"publicHost\":\"tcp.cloudnativehub.app\",\"publicPort\":20417,"
            + "\"url\":\"tcp://tcp.cloudnativehub.app:20417\"}]}";
    private static final String OPEN = "{\"tunnelId\":\"5d0f4f0e-31a6-4a39-8d51-0c0f7f1f6c23\",\"kind\":\"tcp\","
            + "\"remote\":{\"address\":\"203.0.113.9\",\"port\":51234}}";
    private static final String ERROR =
            "{\"code\":\"PORT_UNAVAILABLE\",\"message\":\"Port 20417 is already in use.\",\"ref\":\"default\"}";

    /** Each kind of control payload pretty-printed, as the examples in {@code docs/protocol.md} §7 are. */
    static Stream<Arguments> prettyPrintedExamples() {
        return Stream.of(
                arguments("""
                        {
                          "client": { "name": "jburrow-cli", "version": "0.2.0", "os": "linux", "arch": "amd64" },
                          "capabilities": []
                        }
                        """, HELLO_RECORD),
                arguments("""
                        {
                          "sessionId": "0b8e5c1e-7c55-4c46-9a3e-2f1d0c9e4b7a",
                          "server": { "name": "jburrow-server", "version": "0.2.0" },
                          "capabilities": [],
                          "limits": {
                            "maxFramePayload": 65536,
                            "maxTunnels": 1,
                            "maxConcurrentStreams": 1,
                            "pingIntervalMs": 15000,
                            "idleTimeoutMs": 45000
                          }
                        }
                        """, SERVER_HELLO_RECORD),
                arguments("""
                        {
                          "authtoken": "jb_LOCAL-DEVELOPMENT-ONLY-unlocks-localhost-00",
                          "tunnels": [ { "ref": "default", "kind": "tcp", "remotePort": 20417 } ]
                        }
                        """, REGISTER_RECORD),
                arguments("""
                        {
                          "tunnels": [
                            {
                              "ref": "default",
                              "tunnelId": "5d0f4f0e-31a6-4a39-8d51-0c0f7f1f6c23",
                              "kind": "tcp",
                              "publicHost": "tcp.cloudnativehub.app",
                              "publicPort": 20417,
                              "url": "tcp://tcp.cloudnativehub.app:20417"
                            }
                          ]
                        }
                        """, REGISTERED_RECORD),
                arguments("""
                        { "tunnelId": "5d0f4f0e-31a6-4a39-8d51-0c0f7f1f6c23", "kind": "tcp", \
                        "remote": { "address": "203.0.113.9", "port": 51234 } }
                        """, OPEN_RECORD),
                arguments("""
                        { "code": "PORT_UNAVAILABLE", "message": "Port 20417 is already in use.", "ref": "default" }
                        """, ERROR_RECORD));
    }

    @ParameterizedTest
    @MethodSource("prettyPrintedExamples")
    void readsEveryPrettyPrintedExample(String json, ControlMessage expected) {
        assertThat(read(json, expected.getClass())).isEqualTo(expected);
    }

    @ParameterizedTest
    @MethodSource("prettyPrintedExamples")
    void everyPrettyPrintedExampleRoundTrips(String json, ControlMessage expected) {
        ControlMessage parsed = read(json, expected.getClass());

        assertThat(JSON.read(JSON.write(parsed), expected.getClass())).isEqualTo(expected);
    }

    @Test
    void writesCompactJsonInComponentOrder() {
        assertThat(written(HELLO_RECORD)).isEqualTo(HELLO);
        assertThat(written(SERVER_HELLO_RECORD)).isEqualTo(SERVER_HELLO);
        assertThat(written(REGISTER_RECORD)).isEqualTo(REGISTER);
        assertThat(written(REGISTERED_RECORD)).isEqualTo(REGISTERED);
        assertThat(written(OPEN_RECORD)).isEqualTo(OPEN);
        assertThat(written(ERROR_RECORD)).isEqualTo(ERROR);
    }

    @Test
    void omitsAbsentOptionalFields() {
        assertThat(written(new Hello(new ClientInfo("jburrow-cli", "0.2.0", null, null), List.of())))
                .isEqualTo("{\"client\":{\"name\":\"jburrow-cli\",\"version\":\"0.2.0\"},\"capabilities\":[]}");
        assertThat(written(new Register("jb_x", List.of(new TunnelRequest("default", TunnelKind.TCP, null)))))
                .isEqualTo("{\"authtoken\":\"jb_x\",\"tunnels\":[{\"ref\":\"default\",\"kind\":\"tcp\"}]}");
        assertThat(written(new ErrorBody(ErrorCode.PROTOCOL_ERROR, "Bad frame.", null)))
                .isEqualTo("{\"code\":\"PROTOCOL_ERROR\",\"message\":\"Bad frame.\"}");
    }

    @Test
    void absentOptionalFieldsReadAsNull() {
        Register register = JSON.read(utf8(REGISTER.replace(",\"remotePort\":20417", "")), Register.class);
        ErrorBody error = JSON.read(utf8(ERROR.replace(",\"ref\":\"default\"", "")), ErrorBody.class);

        assertThat(register.tunnels().getFirst().remotePort()).isNull();
        assertThat(error.ref()).isNull();
    }

    @Test
    void roundTripsNonAsciiText() {
        ErrorBody body = new ErrorBody(ErrorCode.PORT_UNAVAILABLE, "Port déjà utilisé ✓ 端口 🚀", null);

        assertThat(JSON.read(JSON.write(body), ErrorBody.class)).isEqualTo(body);
    }

    @Test
    void refusesToWriteAPayloadNoPeerWouldAccept() {
        String host = String.join(".", "a".repeat(61), "b".repeat(61), "c".repeat(61), "d".repeat(61), "e");
        List<RegisteredTunnel> tunnels = IntStream.range(0, 32)
                .mapToObj(i -> RegisteredTunnel.of("t" + i, UUID.randomUUID(), TunnelKind.TCP, host, 20_000 + i))
                .toList();

        assertThatIllegalStateException()
                .isThrownBy(() -> JSON.write(new Registered(tunnels)))
                .withMessageContaining("Registered")
                .withMessageContaining("16384");
    }

    static Stream<Arguments> unknownKeys() {
        return Stream.of(
                arguments(
                        HELLO.replace("{\"client\"", "{\"future\":{\"a\":[1,\"x\",null,true,{}]},\"client\""),
                        HELLO_RECORD),
                arguments(
                        HELLO.replace("\"arch\":\"amd64\"", "\"arch\":\"amd64\",\"distro\":\"fedora\""), HELLO_RECORD),
                arguments(
                        SERVER_HELLO.replace("\"maxTunnels\":1", "\"maxTunnels\":1,\"maxBandwidth\":5.5"),
                        SERVER_HELLO_RECORD),
                arguments(
                        SERVER_HELLO.replace("\"version\":\"0.2.0\"", "\"version\":\"0.2.0\",\"build\":null"),
                        SERVER_HELLO_RECORD),
                arguments(
                        REGISTER.replace("\"ref\":\"default\"", "\"ref\":\"default\",\"subdomain\":\"x\""),
                        REGISTER_RECORD),
                arguments(
                        REGISTERED.replace("\"publicPort\":20417", "\"publicPort\":20417,\"expiresAtMs\":1"),
                        REGISTERED_RECORD),
                arguments(OPEN.replace("\"port\":51234", "\"port\":51234,\"family\":\"ipv4\""), OPEN_RECORD),
                arguments(ERROR.replace("{\"code\"", "{\"retryAfterMs\":1000,\"code\""), ERROR_RECORD),
                arguments(
                        HELLO.replace("{\"client\"", "{\"@class\":\"java.lang.ProcessBuilder\",\"client\""),
                        HELLO_RECORD));
    }

    @ParameterizedTest
    @MethodSource("unknownKeys")
    void ignoresUnknownKeysAtEveryLevel(String json, ControlMessage expected) {
        assertThat(read(json, expected.getClass())).isEqualTo(expected);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "{\"capabilities\":[],\"capabilities\":[],"
                        + "\"client\":{\"name\":\"jburrow-cli\",\"version\":\"0.2.0\"}}",
                "{\"client\":{\"name\":\"jburrow-cli\",\"name\":\"other\",\"version\":\"0.2.0\"},\"capabilities\":[]}",
                "{\"client\":{\"name\":\"jburrow-cli\",\"version\":\"0.2.0\"},\"capabilities\":[],"
                        + "\"future\":{\"a\":1,\"a\":2}}",
                "{\"client\":{\"name\":\"jburrow-cli\",\"version\":\"0.2.0\"},\"capabilities\":[],"
                        + "\"future\":[{\"b\":{\"c\":1,\"c\":1}}]}"
            })
    void rejectsDuplicateKeysAtEveryLevel(String json) {
        assertProtocolError(json, Hello.class);
    }

    @Test
    void rejectsDuplicateKeysInListEntriesAndNestedRecords() {
        assertProtocolError(
                REGISTER.replace("\"ref\":\"default\"", "\"ref\":\"default\",\"ref\":\"default\""), Register.class);
        assertProtocolError(
                SERVER_HELLO.replace("\"maxTunnels\":1", "\"maxTunnels\":1,\"maxTunnels\":1"), ServerHello.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"x", " {}", "{}", "[]", "1", "null", "\n}", ","})
    void rejectsTrailingContent(String trailing) {
        assertProtocolError(HELLO + trailing, Hello.class);
    }

    @Test
    void acceptsSurroundingWhitespace() {
        assertThat(read(" \t\r\n" + HELLO + " \t\r\n", Hello.class)).isEqualTo(HELLO_RECORD);
    }

    @ParameterizedTest
    @ValueSource(strings = {"[]", "[" + "{}" + "]", "\"hello\"", "1", "true", "null", "", "   "})
    void rejectsATopLevelValueThatIsNotAnObject(String json) {
        assertThat(rejection(json, Hello.class))
                .returns(PROTOCOL_ERROR, ProtocolException::code)
                .hasMessage("payload is not a JSON object");
    }

    @Test
    void rejectsMalformedJson() {
        assertProtocolError("{", Hello.class);
        assertProtocolError("{\"client\":}", Hello.class);
        assertProtocolError("{'client':{}}", Hello.class);
        assertProtocolError("{client:{}}", Hello.class);
        assertProtocolError(HELLO.replace("[]", "[,]"), Hello.class);
        assertProtocolError(HELLO.replace("}", ",}"), Hello.class);
        assertProtocolError(HELLO.replace("\"capabilities\"", "/* c */\"capabilities\""), Hello.class);
        assertProtocolError(HELLO.replace("[]", "[NaN]"), Hello.class);
    }

    @Test
    void acceptsNestingOfEightLevels() {
        assertThat(read(helloNestedTo(8), Hello.class)).isEqualTo(HELLO_RECORD);
    }

    @Test
    void rejectsNestingOfNineLevels() {
        assertProtocolError(helloNestedTo(9), Hello.class);
    }

    @Test
    void stringsAreAtMost4096Characters() {
        assertThat(read(helloWithPad("\"" + "s".repeat(4_096) + "\""), Hello.class))
                .isEqualTo(HELLO_RECORD);
        assertProtocolError(helloWithPad("\"" + "s".repeat(4_097) + "\""), Hello.class);
        assertProtocolError(helloWithPad("[\"" + "s".repeat(4_097) + "\"]"), Hello.class);
        assertProtocolError(HELLO.replace("jburrow-cli", "s".repeat(4_097)), Hello.class);
    }

    @Test
    void keysAreAtMost64Characters() {
        assertThat(read(HELLO.replace("{\"client\"", "{\"" + "k".repeat(64) + "\":1,\"client\""), Hello.class))
                .isEqualTo(HELLO_RECORD);
        assertProtocolError(HELLO.replace("{\"client\"", "{\"" + "k".repeat(65) + "\":1,\"client\""), Hello.class);
    }

    @Test
    void numbersAreAtMost20Digits() {
        assertThat(read(helloWithPad("1".repeat(20)), Hello.class)).isEqualTo(HELLO_RECORD);
        assertProtocolError(helloWithPad("1".repeat(21)), Hello.class);
    }

    /** The units docs/protocol.md §7.1 states for each limit, which another implementation has to match. */
    @Test
    void nestingCountsArraysAsLevelsToo() {
        assertThat(read(helloWithPad("[".repeat(7) + "1" + "]".repeat(7)), Hello.class))
                .isEqualTo(HELLO_RECORD);
        assertProtocolError(helloWithPad("[".repeat(8) + "1" + "]".repeat(8)), Hello.class);
    }

    @Test
    void stringLengthCountsUtf16CodeUnitsAfterEscapes() {
        String twoBytes = "\u00e9";
        String surrogatePair = "\uD83D\uDE00";
        assertThat(read(helloWithPad("\"" + twoBytes.repeat(4_096) + "\""), Hello.class))
                .isEqualTo(HELLO_RECORD);
        assertThat(read(helloWithPad("\"" + surrogatePair.repeat(2_048) + "\""), Hello.class))
                .isEqualTo(HELLO_RECORD);
        assertProtocolError(helloWithPad("\"" + surrogatePair.repeat(2_049) + "\""), Hello.class);
        assertThat(read(helloWithPad("\"" + "\\n".repeat(4_096) + "\""), Hello.class))
                .isEqualTo(HELLO_RECORD);
        assertProtocolError(helloWithPad("\"" + "\\n".repeat(4_097) + "\""), Hello.class);
    }

    @Test
    void keyLengthCountsUtf8BytesAfterEscapes() {
        assertThat(read(helloWithKey("\u00e9".repeat(32)), Hello.class)).isEqualTo(HELLO_RECORD);
        assertProtocolError(helloWithKey("\u00e9".repeat(33)), Hello.class);
        assertThat(read(helloWithKey("\\u006b".repeat(64)), Hello.class)).isEqualTo(HELLO_RECORD);
        assertProtocolError(helloWithKey("\\u006b".repeat(65)), Hello.class);
    }

    @Test
    void numberLengthCountsEveryDigitButNotSignsPointOrMarker() {
        assertThat(read(helloWithPad("-" + "1".repeat(20)), Hello.class)).isEqualTo(HELLO_RECORD);
        assertThat(read(helloWithPad("-1." + "1".repeat(19)), Hello.class)).isEqualTo(HELLO_RECORD);
        assertProtocolError(helloWithPad("1." + "1".repeat(20)), Hello.class);
        assertThat(read(helloWithPad("1.0e-" + "1".repeat(18)), Hello.class)).isEqualTo(HELLO_RECORD);
        assertProtocolError(helloWithPad("1.0e" + "1".repeat(19)), Hello.class);
        assertThat(read(helloWithPad("1".repeat(10) + "." + "1".repeat(10)), Hello.class))
                .isEqualTo(HELLO_RECORD);
        assertProtocolError(helloWithPad("1".repeat(10) + "." + "1".repeat(11)), Hello.class);
    }

    /** Jackson recycles its text buffer, and a skipped string is only measured when that buffer grows. */
    @Test
    void stringLimitHoldsForIgnoredValuesWhateverEarlierPayloadsLeftBehind() {
        read(helloWithPad("\"" + "\\n".repeat(4_096) + "\""), Hello.class);

        assertProtocolError(helloWithPad("\"" + "s".repeat(4_097) + "\""), Hello.class);
        assertProtocolError(HELLO.replace("[]}", "[],\"pad\":\"" + "s".repeat(4_097) + "\"}"), Hello.class);
    }

    @Test
    void anEncodingRuleWinsOverAFieldRuleBrokenEarlierInThePayload() {
        String badName = HELLO.replace("jburrow-cli", "not a name");

        assertValidationFailed(badName, Hello.class, "client.name must match [A-Za-z0-9._-]{1,64}");
        assertProtocolError(badName.replace("[]}", "[],\"pad\":\"" + "s".repeat(4_097) + "\"}"), Hello.class);
        assertProtocolError(badName.replace("[]}", "[],\"a\":1,\"a\":2}"), Hello.class);
        assertProtocolError(badName.replace("[]}", "[],\"pad\":" + "1".repeat(21) + "}"), Hello.class);
    }

    @Test
    void escapedNulAndUnpairedSurrogatesAreValidJsonStrings() {
        assertThat(read(helloWithPad("\"\\u0000\""), Hello.class)).isEqualTo(HELLO_RECORD);
        assertThat(read(helloWithPad("\"\\ud800\""), Hello.class)).isEqualTo(HELLO_RECORD);
    }

    @Test
    void payloadIsAtMost16384Bytes() {
        String padded = HELLO + " ".repeat(16_384 - HELLO.length());
        assertThat(read(padded, Hello.class)).isEqualTo(HELLO_RECORD);

        assertThat(rejection(padded + " ", Hello.class)).hasMessage("payload is longer than 16384 bytes");
    }

    @Test
    void payloadLimitCountsBytesNotCharacters() {
        String message = "é".repeat(500);
        String json =
                "{\"code\":\"PROTOCOL_ERROR\",\"message\":\"" + message + "\",\"pad\":\"" + "é".repeat(8_000) + "\"}";
        assertThat(json.length()).isLessThan(16_384);
        assertThat(utf8(json).length).isGreaterThan(16_384);

        assertThat(rejection(json, ErrorBody.class)).hasMessage("payload is longer than 16384 bytes");
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"20417\"", "20417.0", "2.0417e4", "2147483648", "-2147483649", "null", "true", "[20417]"})
    void remotePortMustBeAnIntegerInIntRange(String value) {
        assertProtocolError(REGISTER.replace("20417", value), Register.class);
    }

    @Test
    void remotePortOutsideItsRuleIsAValidationFailure() {
        assertValidationFailed(
                REGISTER.replace("20417", "70000"),
                Register.class,
                "tunnels[0].remotePort must be between 1 and 65535");
        assertValidationFailed(
                REGISTER.replace("20417", "-1"), Register.class, "tunnels[0].remotePort must be between 1 and 65535");
    }

    @ParameterizedTest
    @ValueSource(strings = {"2147483648", "-2147483649", "\"1\"", "1.0", "null", "false"})
    void intComponentsRejectAnythingButAnIntegerInIntRange(String value) {
        assertProtocolError(SERVER_HELLO.replace("\"maxTunnels\":1", "\"maxTunnels\":" + value), ServerHello.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"9223372036854775808", "-9223372036854775809", "\"15000\"", "15000.5", "1.5e4", "null"})
    void longComponentsRejectAnythingButAnIntegerInLongRange(String value) {
        assertProtocolError(SERVER_HELLO.replace("15000", value), ServerHello.class);
    }

    @Test
    void longComponentsAcceptTheWholeLongRange() {
        String json = SERVER_HELLO.replace("45000", "9223372036854775807");

        assertThat(read(json, ServerHello.class).limits().idleTimeoutMs()).isEqualTo(Long.MAX_VALUE);
    }

    @Test
    void unknownKindIsAValidationFailure() {
        assertValidationFailed(
                REGISTER.replace("\"tcp\"", "\"http\""), Register.class, "tunnels[0].kind must be one of [tcp]");
        assertValidationFailed(
                REGISTER.replace("\"tcp\"", "\"TCP\""), Register.class, "tunnels[0].kind must be one of [tcp]");
        assertValidationFailed(OPEN.replace("\"tcp\"", "\"udp\""), Open.class, "kind must be one of [tcp]");
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "1", "null", "[\"tcp\"]", "{}"})
    void kindOfTheWrongJsonTypeIsAProtocolError(String value) {
        assertProtocolError(REGISTER.replace("\"tcp\"", value), Register.class);
    }

    @Test
    void errorCodesAreExactAndScoped() {
        assertValidationFailed(
                ERROR.replace("PORT_UNAVAILABLE", "TOKEN_EXPIRED"), ErrorBody.class, "code must be a known error code");
        assertValidationFailed(
                ERROR.replace("PORT_UNAVAILABLE", "port_unavailable"),
                ErrorBody.class,
                "code must be a known error code");
        assertValidationFailed(
                ERROR.replace("PORT_UNAVAILABLE", "PEER_RESET"),
                ErrorBody.class,
                "code must be a session or upgrade error code");
        assertProtocolError(ERROR.replace("\"PORT_UNAVAILABLE\"", "17"), ErrorBody.class);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "0B8E5C1E-7C55-4C46-9A3E-2F1D0C9E4B7A",
                "0b8e5c1e7c554c469a3e2f1d0c9e4b7a",
                "{0b8e5c1e-7c55-4c46-9a3e-2f1d0c9e4b7a}",
                "C45cHnxVTEaaPi8dDJ5Leg==",
                "not-a-uuid",
                ""
            })
    void uuidsMustBeCanonicalLowerCase(String value) {
        assertValidationFailed(
                SERVER_HELLO.replace("0b8e5c1e-7c55-4c46-9a3e-2f1d0c9e4b7a", value),
                ServerHello.class,
                "sessionId must be a canonical lower-case UUID");
        assertValidationFailed(
                REGISTERED.replace("5d0f4f0e-31a6-4a39-8d51-0c0f7f1f6c23", value),
                Registered.class,
                "tunnels[0].tunnelId must be a canonical lower-case UUID");
    }

    @Test
    void uuidOfTheWrongJsonTypeIsAProtocolError() {
        assertProtocolError(OPEN.replace("\"5d0f4f0e-31a6-4a39-8d51-0c0f7f1f6c23\"", "123"), Open.class);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "\"name\":5",
                "\"name\":true",
                "\"name\":[\"jburrow-cli\"]",
                "\"name\":{}",
                "\"name\":null",
            })
    void stringsAreNeverCoerced(String name) {
        String json = HELLO.replace("\"name\":\"jburrow-cli\"", name);

        assertThat(rejection(json, Hello.class))
                .returns(PROTOCOL_ERROR, ProtocolException::code)
                .message()
                .startsWith("client.name has the wrong JSON type at line 1, column ");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "\"capabilities\":\"x\"",
                "\"capabilities\":[null]",
                "\"capabilities\":[1]",
                "\"capabilities\":{}",
                "\"capabilities\":null"
            })
    void listsAreNeverCoerced(String capabilities) {
        assertProtocolError(HELLO.replace("\"capabilities\":[]", capabilities), Hello.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"\"", "[]", "\"jburrow-cli\"", "null", "1"})
    void objectsAreNeverCoerced(String client) {
        String json = "{\"client\":" + client + ",\"capabilities\":[]}";

        assertProtocolError(json, Hello.class);
    }

    @Test
    void nullIsRejectedEvenForOptionalFields() {
        assertProtocolError(HELLO.replace("\"linux\"", "null"), Hello.class);
        assertProtocolError(ERROR.replace("\"default\"", "null"), ErrorBody.class);
        assertProtocolError(REGISTER.replace("20417", "null"), Register.class);
    }

    @Test
    void missingRequiredFieldsAreValidationFailuresNamingTheField() {
        assertValidationFailed("{\"capabilities\":[]}", Hello.class, "client is required");
        assertValidationFailed(HELLO.replace("\"name\":\"jburrow-cli\",", ""), Hello.class, "client.name is required");
        assertValidationFailed("{\"authtoken\":\"jb_x\"}", Register.class, "tunnels is required");
        assertValidationFailed(
                REGISTER.replace("\"ref\":\"default\",", ""), Register.class, "tunnels[0].ref is required");
        assertValidationFailed(
                SERVER_HELLO.replace("\"maxTunnels\":1,", ""), ServerHello.class, "limits.maxTunnels is required");
        assertValidationFailed(OPEN.replace(",\"port\":51234", ""), Open.class, "remote.port is required");
    }

    @Test
    void propertyNamesAreExact() {
        assertValidationFailed(HELLO.replace("\"client\"", "\"Client\""), Hello.class, "client is required");
        assertValidationFailed(
                REGISTER.replace("\"authtoken\"", "\"authToken\""), Register.class, "authtoken is required");
    }

    @Test
    void fieldRulesAreValidationFailuresNamingTheFieldPath() {
        assertValidationFailed(
                REGISTER.replace("\"ref\":\"default\"", "\"ref\":\"Bad Ref\""),
                Register.class,
                "tunnels[0].ref must match [a-z0-9][a-z0-9-]{0,31}");
        assertValidationFailed(
                REGISTER.replace("}]}", "},{\"ref\":\"default\",\"kind\":\"tcp\"}]}"),
                Register.class,
                "tunnels[1].ref must be unique");
        assertValidationFailed(
                HELLO.replace("\"linux\"", "\"Linux\""), Hello.class, "client.os must match [a-z0-9_]{1,32}");
        assertValidationFailed(HELLO.replace("[]", "[\"a\",\"a\"]"), Hello.class, "capabilities[1] must be unique");
        assertValidationFailed(
                OPEN.replace("203.0.113.9", "localhost"),
                Open.class,
                "remote.address must be an IPv4 or IPv6 address literal");
        assertValidationFailed(
                REGISTERED.replace("tcp://tcp.cloudnativehub.app:20417", "tcp://elsewhere:1"),
                Registered.class,
                "tunnels[0].url must be <kind>://<publicHost>:<publicPort>");
        assertValidationFailed(
                SERVER_HELLO.replace("45000", "29999"),
                ServerHello.class,
                "limits.idleTimeoutMs must be at least twice pingIntervalMs");
        assertValidationFailed(REGISTER.replace(TOKEN, ""), Register.class, "authtoken must be 1 to 256 characters");
    }

    static Stream<Arguments> notUtf8() {
        byte[] hello = utf8(HELLO);
        return Stream.of(
                arguments("lone continuation byte", replaceInName(hello, (byte) 0x80)),
                arguments("invalid byte 0xFF", replaceInName(hello, (byte) 0xFF)),
                arguments("overlong '/'", replaceInName(hello, (byte) 0xC0, (byte) 0xAF)),
                arguments("overlong NUL", replaceInName(hello, (byte) 0xC0, (byte) 0x80)),
                arguments("encoded surrogate", replaceInName(hello, (byte) 0xED, (byte) 0xA0, (byte) 0x80)),
                arguments("truncated sequence", replaceInName(hello, (byte) 0xE2, (byte) 0x82)),
                arguments("beyond U+10FFFF", replaceInName(hello, (byte) 0xF4, (byte) 0x90, (byte) 0x80, (byte) 0x80)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("notUtf8")
    void rejectsInvalidUtf8(String description, byte[] payload) {
        assertThat(rejection(payload, Hello.class))
                .returns(PROTOCOL_ERROR, ProtocolException::code)
                .hasMessage("payload is not valid UTF-8");
    }

    @ParameterizedTest
    @ValueSource(strings = {"UTF-16BE", "UTF-16LE", "UTF-16", "UTF-32BE", "UTF-32LE"})
    void rejectsOtherEncodings(String charset) {
        assertProtocolError(HELLO.getBytes(Charset.forName(charset)), Hello.class);
    }

    @Test
    void rejectsAByteOrderMark() {
        assertProtocolError(("﻿" + HELLO).getBytes(UTF_8), Hello.class);
    }

    @Test
    void readsFromAStream() {
        assertThat(JSON.read(new ByteArrayInputStream(utf8(REGISTER)), Register.class))
                .isEqualTo(REGISTER_RECORD);
    }

    @Test
    void streamIsHeldToTheSameLimitWithoutReadingItAll() {
        byte[] oversized = utf8(HELLO + " ".repeat(20_000));
        ByteArrayInputStream in = new ByteArrayInputStream(oversized);

        assertThat(catchThrowable(() -> JSON.read(in, Hello.class)))
                .isInstanceOf(ProtocolException.class)
                .hasMessage("payload is longer than 16384 bytes");
        assertThat(in.available()).isEqualTo(oversized.length - 16_385);
    }

    @Test
    void streamFailureIsNotBlamedOnThePeer() {
        InputStream broken = new InputStream() {
            @Override
            public int read() throws IOException {
                throw new IOException("closed");
            }
        };

        assertThatExceptionOfType(UncheckedIOException.class).isThrownBy(() -> JSON.read(broken, Hello.class));
    }

    @Test
    void protocolErrorsGiveTheLocation() {
        assertThat(rejection(HELLO.replace("[]", "[1]"), Hello.class).getMessage())
                .isEqualTo("capabilities[0] has the wrong JSON type at line 1, column 96");
        assertThat(rejection("{\n  \"client\": }", Hello.class).getMessage())
                .matches("payload is not well-formed JSON or repeats a key at line 2, column \\d+");
    }

    private static final String MARKER = "zqxmarker";

    static Stream<Arguments> payloadsCarryingAMarker() {
        String client = "{\"name\":\"jburrow-cli\",\"version\":\"0.2.0\"}";
        return Stream.of(
                arguments(HELLO.replace("jburrow-cli", MARKER + "!"), Hello.class),
                arguments(HELLO.replace("jburrow-cli", MARKER.repeat(500)), Hello.class),
                arguments(HELLO.replace("jburrow-cli", "\"" + MARKER), Hello.class),
                arguments("{\"client\":" + client + ",\"capabilities\":[],\"" + MARKER + "\":}", Hello.class),
                arguments("{\"" + MARKER + "\":tru,\"client\":" + client + "}", Hello.class),
                arguments("{\"" + MARKER + "\":1,\"" + MARKER + "\":2}", Hello.class),
                arguments("{\"" + MARKER.repeat(8) + "\":1}", Hello.class),
                arguments(
                        "{\"" + MARKER + "\":{\"a\":{\"b\":{\"c\":{\"d\":{\"e\":{\"f\":{\"g\":1}}}}}}}}", Hello.class),
                arguments("{\"client\":\"" + MARKER + "\",\"capabilities\":[]}", Hello.class),
                arguments(HELLO.replace("[]", "[\"" + MARKER.toUpperCase(Locale.ROOT) + "\"]"), Hello.class),
                arguments(HELLO + MARKER, Hello.class),
                arguments(REGISTER.replace("20417", "\"" + MARKER + "\""), Register.class),
                arguments(REGISTER.replace("\"tcp\"", "\"" + MARKER + "\""), Register.class),
                arguments(REGISTER.replace(TOKEN, "jb_" + MARKER.repeat(30)), Register.class),
                arguments(REGISTER.replace("default", MARKER.toUpperCase(Locale.ROOT)), Register.class),
                arguments(ERROR.replace("PORT_UNAVAILABLE", MARKER.toUpperCase(Locale.ROOT)), ErrorBody.class),
                arguments(SERVER_HELLO.replace("0b8e5c1e-7c55-4c46-9a3e-2f1d0c9e4b7a", MARKER), ServerHello.class),
                arguments(OPEN.replace("203.0.113.9", MARKER), Open.class),
                arguments(REGISTERED.replace("tcp.cloudnativehub.app\"", MARKER + "\""), Registered.class));
    }

    @ParameterizedTest
    @MethodSource("payloadsCarryingAMarker")
    void errorMessagesNeverQuoteThePayload(String json, Class<? extends ControlMessage> type) {
        ProtocolException rejected = rejection(json, type);

        assertThat(rejected.getMessage()).doesNotContainIgnoringCase(MARKER).doesNotContain("jb_");
    }

    @Test
    void everyStrictnessSettingIsExplicit() {
        JsonMapper mapper = JSON.mapper();

        assertThat(mapper.isEnabled(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES))
                .isFalse();
        assertThat(mapper.isEnabled(StreamReadFeature.STRICT_DUPLICATE_DETECTION))
                .isTrue();
        assertThat(mapper.isEnabled(DeserializationFeature.FAIL_ON_TRAILING_TOKENS))
                .isTrue();
        assertThat(mapper.isEnabled(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES))
                .isTrue();
        assertThat(mapper.isEnabled(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES))
                .isFalse();
        assertThat(mapper.isEnabled(DeserializationFeature.ACCEPT_FLOAT_AS_INT)).isFalse();
        assertThat(mapper.isEnabled(MapperFeature.ALLOW_COERCION_OF_SCALARS)).isFalse();
        assertThat(mapper.isEnabled(MapperFeature.ACCEPT_CASE_INSENSITIVE_PROPERTIES))
                .isFalse();
        assertThat(mapper.isEnabled(MapperFeature.ACCEPT_CASE_INSENSITIVE_ENUMS))
                .isFalse();
        assertThat(mapper.isEnabled(MapperFeature.ACCEPT_CASE_INSENSITIVE_VALUES))
                .isFalse();
        assertThat(mapper.isEnabled(DeserializationFeature.ACCEPT_SINGLE_VALUE_AS_ARRAY))
                .isFalse();
        assertThat(mapper.isEnabled(EnumFeature.FAIL_ON_NUMBERS_FOR_ENUMS)).isTrue();
        assertThat(mapper.isEnabled(StreamReadFeature.INCLUDE_SOURCE_IN_LOCATION))
                .isFalse();
        assertThat(mapper.isEnabled(SerializationFeature.INDENT_OUTPUT)).isFalse();
        assertThat(mapper.serializationConfig().getDefaultPropertyInclusion().getValueInclusion())
                .isEqualTo(JsonInclude.Include.NON_NULL);
        assertThat(mapper.deserializationConfig().getDefaultNullHandling().getContentNulls())
                .isEqualTo(Nulls.FAIL);

        StreamReadConstraints limits = mapper.tokenStreamFactory().streamReadConstraints();
        assertThat(limits.getMaxNestingDepth()).isEqualTo(8);
        assertThat(limits.getMaxStringLength()).isEqualTo(4_096);
        assertThat(limits.getMaxNameLength()).isEqualTo(64);
        assertThat(limits.getMaxNumberLength()).isEqualTo(20);
        assertThat(limits.getMaxDocumentLength()).isEqualTo(16_384);
    }

    private static <T extends ControlMessage> T read(String json, Class<T> type) {
        return JSON.read(utf8(json), type);
    }

    private static String written(ControlMessage message) {
        return new String(JSON.write(message), UTF_8);
    }

    private static void assertProtocolError(String json, Class<? extends ControlMessage> type) {
        assertProtocolError(utf8(json), type);
    }

    private static void assertProtocolError(byte[] payload, Class<? extends ControlMessage> type) {
        assertThat(rejection(payload, type).code()).isEqualTo(PROTOCOL_ERROR);
    }

    private static void assertValidationFailed(String json, Class<? extends ControlMessage> type, String message) {
        assertThat(rejection(json, type))
                .returns(VALIDATION_FAILED, ProtocolException::code)
                .hasMessage(message);
    }

    private static ProtocolException rejection(String json, Class<? extends ControlMessage> type) {
        return rejection(utf8(json), type);
    }

    private static ProtocolException rejection(byte[] payload, Class<? extends ControlMessage> type) {
        Throwable thrown = catchThrowable(() -> JSON.read(payload, type));
        assertThat(thrown).isInstanceOf(ProtocolException.class);
        ProtocolException rejected = (ProtocolException) thrown;
        assertThat(rejected.scope()).isEqualTo(ErrorScope.SESSION);
        return rejected;
    }

    /** A valid HELLO whose deepest object is {@code depth} levels down, counting the payload itself as one. */
    private static String helloNestedTo(int depth) {
        String pad = "{\"a\":".repeat(depth - 2) + "{}" + "}".repeat(depth - 2);
        return helloWithPad(pad);
    }

    private static String helloWithPad(String value) {
        return HELLO.replace("{\"client\"", "{\"pad\":" + value + ",\"client\"");
    }

    private static String helloWithKey(String key) {
        return HELLO.replace("{\"client\"", "{\"" + key + "\":1,\"client\"");
    }

    /** {@code hello} with the bytes of the client name's first character replaced by {@code bytes}. */
    private static byte[] replaceInName(byte[] hello, byte... bytes) {
        String json = new String(hello, StandardCharsets.US_ASCII);
        int at = json.indexOf("jburrow-cli");
        byte[] result = new byte[hello.length - 1 + bytes.length];
        System.arraycopy(hello, 0, result, 0, at);
        System.arraycopy(bytes, 0, result, at, bytes.length);
        System.arraycopy(hello, at + 1, result, at + bytes.length, hello.length - at - 1);
        return result;
    }

    private static byte[] utf8(String json) {
        return json.getBytes(UTF_8);
    }
}
