package io.jburrow.cli;

import static org.assertj.core.api.Assertions.assertThat;

import io.jburrow.cli.output.JsonLinesOutput;
import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Model.OptionSpec;
import picocli.CommandLine.Model.PositionalParamSpec;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Keeps the CLI's native-image metadata in step with the code that needs it. Jackson writes the JSON event records
 * reflectively and the build info and logger settings are classpath resources, so a missing entry only fails in the
 * native binary. The commands' own configuration is picocli-codegen's, generated at compile time; it is checked here
 * too, because a build that compiled only some classes generates it for those alone (see the module's POM).
 */
class NativeImageMetadataTest {

    private static final String DIRECTORY = "/META-INF/native-image/io.jburrow/jburrow-cli/";
    private static final String GENERATED = "/META-INF/native-image/picocli-generated/io.jburrow/jburrow-cli/";
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
    void picocliGeneratedReflectionForEveryCommandAndWhatItInstantiates() throws IOException {
        // The test classes have a file of the same name, for the fixtures' commands, ahead on the classpath.
        Path file = mainOutput().resolve(GENERATED.substring(1) + "reflect-config.json");
        assertThat(file).as("picocli-codegen's reflection configuration").isRegularFile();
        Set<String> generated = new TreeSet<>();
        JsonMapper.shared()
                .readTree(file.toFile())
                .forEach(entry -> generated.add(entry.path("name").asString()));

        assertThat(generated).containsAll(commandClasses());
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

    /**
     * Every class picocli creates or reads reflectively for the real command tree: each command and the classes it
     * extends within the CLI, its version provider, and each option's and parameter's converter.
     */
    private static Set<String> commandClasses() {
        Set<String> classes = new TreeSet<>();
        Deque<CommandLine> commands = new ArrayDeque<>(List.of(JBurrowCli.commandLine()));
        while (!commands.isEmpty()) {
            CommandSpec spec = commands.pop().getCommandSpec();
            for (Class<?> type = spec.userObject().getClass();
                    type.getPackageName().startsWith("io.jburrow");
                    type = type.getSuperclass()) {
                classes.add(type.getName());
            }
            if (spec.versionProvider() != null) {
                classes.add(spec.versionProvider().getClass().getName());
            }
            for (OptionSpec option : spec.options()) {
                Arrays.stream(option.converters())
                        .forEach(converter -> classes.add(converter.getClass().getName()));
            }
            for (PositionalParamSpec parameter : spec.positionalParameters()) {
                Arrays.stream(parameter.converters())
                        .forEach(converter -> classes.add(converter.getClass().getName()));
            }
            commands.addAll(spec.subcommands().values());
        }
        assertThat(classes).as("classes picocli instantiates").contains(BuildInfo.class.getName());
        return classes;
    }

    /** Where the main classes were compiled to, and picocli-codegen wrote its files. */
    private static Path mainOutput() {
        try {
            return Path.of(BuildInfo.class
                    .getProtectionDomain()
                    .getCodeSource()
                    .getLocation()
                    .toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
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
