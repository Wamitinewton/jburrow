package io.jburrow.protocol.adapter.jackson;

import static org.assertj.core.api.Assertions.assertThat;

import io.jburrow.protocol.control.ControlMessage;
import io.jburrow.protocol.control.UpgradeRefusal;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Keeps {@code reflect-config.json} in step with the payload records. Jackson builds the records reflectively, so a
 * native binary without an entry fails at runtime; checkpoint 15 proves the file in a real binary.
 */
class NativeImageMetadataTest {

    private static final String CONFIG = "/META-INF/native-image/io.jburrow/jburrow-protocol/reflect-config.json";
    private static final String PROTOCOL_PACKAGE = "io.jburrow.protocol.";
    private static final Set<String> RECORD_FLAGS =
            Set.of("allDeclaredConstructors", "allDeclaredMethods", "allDeclaredFields", "queryAllDeclaredMethods");
    private static final Set<String> ENUM_FLAGS = Set.of("allDeclaredFields", "allDeclaredMethods");

    @Test
    void listsEveryRecordAndEnumUsedInAPayload() throws IOException {
        assertThat(entries().keySet())
                .as("types in %s", CONFIG)
                .containsAll(payloadTypes().stream().map(Class::getName).toList());
    }

    @Test
    void listsNothingElse() throws IOException {
        Set<String> expected =
                new TreeSet<>(payloadTypes().stream().map(Class::getName).toList());

        assertThat(entries().keySet()).as("types in %s", CONFIG).isSubsetOf(expected);
    }

    @Test
    void grantsWhatJacksonNeeds() throws IOException {
        Map<String, JsonNode> entries = entries();
        for (Class<?> type : payloadTypes()) {
            JsonNode entry = entries.get(type.getName());
            Set<String> flags = type.isEnum() ? ENUM_FLAGS : RECORD_FLAGS;
            for (String flag : flags) {
                assertThat(entry.path(flag).asBoolean(false))
                        .as("%s in %s for %s", flag, CONFIG, type.getName())
                        .isTrue();
            }
        }
    }

    @Test
    void findsTheWholePayloadModel() {
        assertThat(payloadTypes())
                .extracting(Class::getSimpleName)
                .contains(
                        "Hello",
                        "ClientInfo",
                        "SessionLimits",
                        "Windows",
                        "TunnelRequest",
                        "RemoteAddress",
                        "TunnelKind")
                .contains("UpgradeRefusal")
                .contains("ErrorCode");
    }

    /**
     * The payload records, the refused-upgrade body (which a client reads too), and every protocol record or enum
     * reachable through their components.
     */
    private static Set<Class<?>> payloadTypes() {
        Set<Class<?>> found = new LinkedHashSet<>();
        Deque<Type> pending = new ArrayDeque<>(List.of(ControlMessage.class.getPermittedSubclasses()));
        pending.add(UpgradeRefusal.class);
        while (!pending.isEmpty()) {
            Type type = pending.pop();
            if (type instanceof ParameterizedType parameterized) {
                pending.addAll(List.of(parameterized.getActualTypeArguments()));
            } else if (type instanceof Class<?> c && isProtocolModel(c) && found.add(c) && c.isRecord()) {
                for (RecordComponent component : c.getRecordComponents()) {
                    pending.push(component.getGenericType());
                }
            }
        }
        return found;
    }

    private static boolean isProtocolModel(Class<?> type) {
        return (type.isRecord() || type.isEnum()) && type.getName().startsWith(PROTOCOL_PACKAGE);
    }

    private static Map<String, JsonNode> entries() throws IOException {
        try (InputStream in = NativeImageMetadataTest.class.getResourceAsStream(CONFIG)) {
            assertThat(in).as(CONFIG).isNotNull();
            Map<String, JsonNode> entries = new LinkedHashMap<>();
            for (JsonNode entry : JsonMapper.shared().readTree(in)) {
                entries.put(entry.path("name").asString(), entry);
            }
            return entries;
        }
    }
}
