package io.jburrow.server.config;

import java.util.Map;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;

/**
 * Settings that no longer exist. The binder ignores unknown keys, so an environment that still sets one of these would
 * quietly lose a setting it relies on; startup fails instead, naming the replacement.
 */
final class RemovedProperties {

    /** Each removed key and the key that replaces it. */
    static final Map<String, String> REPLACED = Map.of("jburrow.http.name-hold", "jburrow.session.resume-window");

    private RemovedProperties() {}

    /**
     * Looks each key up through the binder, so every form that would have bound it counts, an environment variable
     * included.
     *
     * @throws IllegalStateException naming the first removed key that is set and its replacement
     */
    static void refuse(Environment environment) {
        Binder binder = Binder.get(environment);
        REPLACED.forEach((removed, replacement) -> {
            if (binder.bind(removed, String.class).isBound()) {
                throw new IllegalStateException(removed + " is replaced by " + replacement);
            }
        });
    }
}
