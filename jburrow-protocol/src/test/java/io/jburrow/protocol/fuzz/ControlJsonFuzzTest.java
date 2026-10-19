package io.jburrow.protocol.fuzz;

import static org.assertj.core.api.Assertions.assertThat;

import com.code_intelligence.jazzer.junit.FuzzTest;
import io.jburrow.protocol.adapter.jackson.ControlJson;
import io.jburrow.protocol.control.ControlMessage;
import io.jburrow.protocol.wire.ErrorCode;
import io.jburrow.protocol.wire.ErrorScope;
import io.jburrow.protocol.wire.ProtocolException;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

/**
 * Reads arbitrary bytes as every control payload type, the way the codec hands a frame's payload to
 * {@link ControlJson}.
 *
 * <p>The property: each read returns a record or throws a session-scoped {@link ProtocolException} with
 * {@code PROTOCOL_ERROR} or {@code VALIDATION_FAILED}, and nothing else. Any record returned writes and reads back to
 * an equal record. The payload types are the permitted subtypes of {@link ControlMessage}, so a new one is fuzzed
 * without a change here.
 */
class ControlJsonFuzzTest {

    private static final Set<ErrorCode> PAYLOAD_ERRORS = Set.of(ErrorCode.PROTOCOL_ERROR, ErrorCode.VALIDATION_FAILED);

    private static final ControlJson JSON = ControlJson.create();
    private static final List<Class<? extends ControlMessage>> PAYLOAD_TYPES = Arrays.stream(
                    ControlMessage.class.getPermittedSubclasses())
            .<Class<? extends ControlMessage>>map(type -> type.asSubclass(ControlMessage.class))
            .toList();

    @FuzzTest(maxDuration = "2m")
    void read(byte[] payload) {
        for (Class<? extends ControlMessage> type : PAYLOAD_TYPES) {
            ControlMessage message = readOrNull(payload, type);
            if (message != null) {
                assertThat(JSON.read(JSON.write(message), type))
                        .as("%s written and read back", type.getSimpleName())
                        .isEqualTo(message);
            }
        }
    }

    private static ControlMessage readOrNull(byte[] payload, Class<? extends ControlMessage> type) {
        try {
            return JSON.read(payload, type);
        } catch (ProtocolException rejected) {
            assertThat(rejected.scope()).isEqualTo(ErrorScope.SESSION);
            assertThat(rejected.code()).isIn(PAYLOAD_ERRORS);
            return null;
        }
    }
}
