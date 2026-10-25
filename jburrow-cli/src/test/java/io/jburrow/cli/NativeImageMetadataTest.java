package io.jburrow.cli;

import static org.assertj.core.api.Assertions.assertThat;

import io.jburrow.cli.output.JsonLinesOutput;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Keeps the CLI's native-image metadata in step with the code that needs it. Jackson writes the JSON event records
 * reflectively and the build info and logger settings are classpath resources, so a missing entry only fails in the
 * native binary.
 */
class NativeImageMetadataTest {

    private static final String DIRECTORY = "/META-INF/native-image/io.jburrow/jburrow-cli/";
    private static final Set<String> RECORD_FLAGS =
            Set.of("allDeclaredConstructors", "allDeclaredMethods", "allDeclaredFields", "queryAllDeclaredMethods");

    /** Read by {@link BuildInfo} and by slf4j-simple. */
    private static final List<String> RESOURCES =
            List.of("jburrow-cli.properties", "git.properties", "simplelogger.properties");

    @Test
    void reflectionListsExactlyTheJsonEventRecords() throws IOException {
        assertThat(reflectEntries().keySet()).containsExactlyInAnyOrderElementsOf(eventRecords());
    }

    @Test
    void reflectionGrantsWhatJacksonNeeds() throws IOException {
        for (JsonNode entry : reflectEntries().values()) {
            for (String flag : RECORD_FLAGS) {
                assertThat(entry.path(flag).asBoolean(false))
                        .as("%s for %s", flag, entry.path("name").asString())
                        .isTrue();
            }
        }
    }

    @Test
    void registersEveryResourceTheCliReads() throws IOException {
        List<Pattern> patterns = read("resource-config.json")
                .path("resources")
                .path("includes")
                .valueStream()
                .map(include -> Pattern.compile(include.path("pattern").asString()))
                .toList();

        assertThat(RESOURCES)
                .allSatisfy(resource -> assertThat(patterns)
                        .as(resource)
                        .anyMatch(pattern -> pattern.matcher(resource).matches()));
        assertThat(patterns).hasSameSizeAs(RESOURCES);
    }

    private static List<String> eventRecords() {
        List<String> records = Arrays.stream(JsonLinesOutput.class.getDeclaredClasses())
                .filter(Class::isRecord)
                .map(Class::getName)
                .toList();
        assertThat(records).as("event records in JsonLinesOutput").isNotEmpty();
        return records;
    }

    private static Map<String, JsonNode> reflectEntries() throws IOException {
        Map<String, JsonNode> entries = new LinkedHashMap<>();
        for (JsonNode entry : read("reflect-config.json")) {
            entries.put(entry.path("name").asString(), entry);
        }
        return entries;
    }

    private static JsonNode read(String file) throws IOException {
        try (InputStream in = NativeImageMetadataTest.class.getResourceAsStream(DIRECTORY + file)) {
            assertThat(in).as(DIRECTORY + file).isNotNull();
            return JsonMapper.shared().readTree(in);
        }
    }
}
