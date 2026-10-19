package io.jburrow.protocol.testsupport;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Loads the conformance vectors in {@code io/jburrow/protocol/vectors}. See {@code vectors/README.md}. */
public final class ConformanceVectors {

    private static final String VECTORS = "/io/jburrow/protocol/vectors/";

    private ConformanceVectors() {}

    /** The vectors that decode to a frame. */
    public static List<JsonNode> valid() {
        return load("valid.json");
    }

    /** The vectors that fail with an error code. */
    public static List<JsonNode> invalid() {
        return load("invalid.json");
    }

    /** Every vector, valid ones first. */
    public static List<JsonNode> all() {
        List<JsonNode> all = new ArrayList<>(valid());
        all.addAll(invalid());
        return all;
    }

    private static List<JsonNode> load(String file) {
        try (InputStream in = ConformanceVectors.class.getResourceAsStream(VECTORS + file)) {
            if (in == null) {
                throw new IllegalStateException("Missing conformance vectors " + VECTORS + file);
            }
            List<JsonNode> vectors = new ArrayList<>();
            JsonMapper.shared().readTree(in).forEach(vectors::add);
            return List.copyOf(vectors);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
