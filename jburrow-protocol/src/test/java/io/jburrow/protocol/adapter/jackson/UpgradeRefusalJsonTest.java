package io.jburrow.protocol.adapter.jackson;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import io.jburrow.protocol.control.UpgradeRefusal;
import io.jburrow.protocol.wire.ErrorCode;
import io.jburrow.protocol.wire.ProtocolException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class UpgradeRefusalJsonTest {

    private static final ControlJson JSON = ControlJson.create();
    private static final Instant AT = Instant.parse("2026-10-19T10:15:30Z");

    /** The example in {@code docs/protocol.md} §3.5. */
    private static final String SPECIFICATION_EXAMPLE = """
            {
              "success": false,
              "code": "UNSUPPORTED_PROTOCOL_VERSION",
              "message": "This server speaks jburrow.v1-draft.2. Upgrade the jburrow CLI.",
              "supported": ["jburrow.v1-draft.2"],
              "timestamp": "2026-10-19T10:15:30Z",
              "path": "/v1/connect"
            }
            """;

    @Test
    void readsTheSpecificationExample() {
        UpgradeRefusal refusal = JSON.readRefusal(utf8(SPECIFICATION_EXAMPLE));

        assertThat(refusal)
                .isEqualTo(UpgradeRefusal.unsupportedVersion(
                        "This server speaks jburrow.v1-draft.2. Upgrade the jburrow CLI.",
                        List.of("jburrow.v1-draft.2"),
                        AT,
                        "/v1/connect"));
    }

    @Test
    void writesCompactJsonInComponentOrder() {
        UpgradeRefusal refusal =
                UpgradeRefusal.unsupportedVersion("Upgrade.", List.of("jburrow.v1-draft.1"), AT, "/v1/connect");

        assertThat(new String(JSON.writeRefusal(refusal), StandardCharsets.UTF_8))
                .isEqualTo("{\"success\":false,\"code\":\"UNSUPPORTED_PROTOCOL_VERSION\",\"message\":\"Upgrade.\","
                        + "\"supported\":[\"jburrow.v1-draft.1\"],\"timestamp\":\"2026-10-19T10:15:30Z\","
                        + "\"path\":\"/v1/connect\"}");
    }

    @Test
    void omitsSupportedForOtherCodes() {
        UpgradeRefusal refusal = UpgradeRefusal.of(ErrorCode.SERVER_AT_CAPACITY, "Busy.", AT, "/v1/connect");

        String json = new String(JSON.writeRefusal(refusal), StandardCharsets.UTF_8);

        assertThat(json).doesNotContain("supported");
        assertThat(JSON.readRefusal(utf8(json))).isEqualTo(refusal);
    }

    @Test
    void ignoresUnknownKeys() {
        String json = SPECIFICATION_EXAMPLE.replace("\"success\"", "\"retryAfter\": 5, \"success\"");

        assertThat(JSON.readRefusal(utf8(json)).code()).isEqualTo(ErrorCode.UNSUPPORTED_PROTOCOL_VERSION);
    }

    @Test
    void refusesABodyWithoutAKnownCode() {
        assertUnreadable(SPECIFICATION_EXAMPLE.replace("UNSUPPORTED_PROTOCOL_VERSION", "TEAPOT"));
        assertUnreadable(SPECIFICATION_EXAMPLE.replace("\"UNSUPPORTED_PROTOCOL_VERSION\"", "null"));
        assertUnreadable("<html>Bad gateway</html>");
        assertUnreadable("");
    }

    @Test
    void refusesASessionCode() {
        assertUnreadable(SPECIFICATION_EXAMPLE.replace("UNSUPPORTED_PROTOCOL_VERSION", "AUTHTOKEN_INVALID"));
    }

    private static void assertUnreadable(String body) {
        assertThatExceptionOfType(ProtocolException.class).isThrownBy(() -> JSON.readRefusal(utf8(body)));
    }

    private static byte[] utf8(String json) {
        return json.getBytes(StandardCharsets.UTF_8);
    }
}
