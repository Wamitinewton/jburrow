package io.jburrow.protocol.fuzz;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.argumentSet;

import io.jburrow.protocol.testsupport.ConformanceVectors;
import io.jburrow.protocol.testsupport.Hex;
import io.jburrow.protocol.wire.FrameLimits;
import io.jburrow.protocol.wire.FrameType;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JsonNode;

/**
 * Keeps the fuzz targets' seed corpus in step with the conformance vectors, so the fuzzer starts from every frame
 * shape the protocol defines and every vector stays a regression input.
 *
 * <p>Every vector's message is a seed for the decoder target of the role that receives it, and every control payload
 * among them is a seed for {@link ControlJsonFuzzTest}, each file named after its vector. A file whose name starts
 * with {@value #FINDING_PREFIX} is a past fuzzing finding, kept for ever, and this test leaves it alone. Anything else
 * in an inputs directory fails the test.
 *
 * <p>Run with {@code -Dupdate.seeds=true} to write the seeds into the source tree from the vectors, then commit them. A
 * seed whose vector was renamed or removed is reported, not deleted.
 */
class SeedCorpusTest {

    static final String FINDING_PREFIX = "finding-";

    private static final String UPDATE_PROPERTY = "update.seeds";
    private static final Path FUZZ_RESOURCES =
            Path.of(System.getProperty("basedir", ".")).resolve("src/test/resources/io/jburrow/protocol/fuzz");
    private static final String DECODE_AS_SERVER = "FrameCodecFuzzTestInputs/decodeAsServer";
    private static final String DECODE_AS_CLIENT = "FrameCodecFuzzTestInputs/decodeAsClient";
    private static final String CONTROL_JSON_READ = "ControlJsonFuzzTestInputs/read";

    /** The frame types whose payload the codec hands to {@code ControlJson}. */
    private static final Set<FrameType> JSON_PAYLOAD_TYPES =
            EnumSet.of(FrameType.HELLO, FrameType.REGISTER, FrameType.OPEN, FrameType.HEADERS, FrameType.ERROR);

    static Stream<Arguments> corpora() {
        Map<String, Map<String, byte[]>> seeds = new TreeMap<>();
        for (JsonNode vector : ConformanceVectors.all()) {
            String name = vector.path("name").asString();
            assertThat(name).as("vector name usable as a file name").matches("[a-z0-9][a-z0-9.-]*");
            byte[] message = Hex.decode(vector.path("hex").asString());

            String decoder = "server".equals(vector.path("role").asString()) ? DECODE_AS_SERVER : DECODE_AS_CLIENT;
            seeds.computeIfAbsent(decoder, ignored -> new TreeMap<>()).put(name, message);
            if (carriesJsonPayload(message)) {
                seeds.computeIfAbsent(CONTROL_JSON_READ, ignored -> new TreeMap<>())
                        .put(name, Arrays.copyOfRange(message, FrameLimits.HEADER_LENGTH, message.length));
            }
        }
        return seeds.entrySet().stream()
                .map(corpus -> argumentSet(corpus.getKey(), corpus.getKey(), corpus.getValue()));
    }

    @ParameterizedTest
    @MethodSource("corpora")
    void seedsMatchTheConformanceVectors(String inputs, Map<String, byte[]> expected) {
        Path directory = FUZZ_RESOURCES.resolve(inputs);
        if (Boolean.getBoolean(UPDATE_PROPERTY)) {
            rewrite(directory, expected);
        }
        Map<String, byte[]> actual = read(directory);

        List<String> missing = new ArrayList<>();
        List<String> different = new ArrayList<>();
        expected.forEach((name, bytes) -> {
            if (!actual.containsKey(name)) {
                missing.add(name);
            } else if (!Arrays.equals(actual.get(name), bytes)) {
                different.add(name);
            }
        });
        List<String> unexpected = actual.keySet().stream()
                .filter(name -> !expected.containsKey(name) && !name.startsWith(FINDING_PREFIX))
                .toList();

        String regenerate = "; regenerate the seeds with -D" + UPDATE_PROPERTY + "=true";
        assertThat(missing).as("seeds missing from %s%s", directory, regenerate).isEmpty();
        assertThat(different)
                .as("seeds in %s that differ from their vector%s", directory, regenerate)
                .isEmpty();
        assertThat(unexpected)
                .as(
                        "files in %s that are neither a vector's seed nor named %s<description>; rename a fuzzing"
                                + " finding (see CONTRIBUTING.md §Fuzzing), or delete a seed whose vector is gone",
                        directory, FINDING_PREFIX)
                .isEmpty();
    }

    private static boolean carriesJsonPayload(byte[] message) {
        return message.length >= FrameLimits.HEADER_LENGTH
                && FrameType.fromCode(Byte.toUnsignedInt(message[4]))
                        .filter(JSON_PAYLOAD_TYPES::contains)
                        .isPresent();
    }

    private static Map<String, byte[]> read(Path directory) {
        Map<String, byte[]> files = new TreeMap<>();
        if (!Files.isDirectory(directory)) {
            return files;
        }
        try (Stream<Path> listing = Files.list(directory)) {
            for (Path file : listing.filter(Files::isRegularFile).toList()) {
                files.put(file.getFileName().toString(), Files.readAllBytes(file));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return files;
    }

    /**
     * Writes every expected seed. Deletes nothing: a file the test doesn't expect may be a crashing input Jazzer just
     * wrote, which has to be looked at, not lost.
     */
    private static void rewrite(Path directory, Map<String, byte[]> expected) {
        try {
            Files.createDirectories(directory);
            for (Map.Entry<String, byte[]> seed : expected.entrySet()) {
                Files.write(directory.resolve(seed.getKey()), seed.getValue());
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
