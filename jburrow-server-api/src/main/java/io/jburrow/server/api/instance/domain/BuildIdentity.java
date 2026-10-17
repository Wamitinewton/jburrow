package io.jburrow.server.api.instance.domain;

import java.util.List;
import java.util.Objects;

/**
 * What is running: the build version, the git commit it was built from, and the active profiles.
 *
 * <p>Profiles are stored comma-joined, so a profile name may not contain a comma.
 */
public record BuildIdentity(String version, String commit, List<String> profiles) {

    public BuildIdentity {
        requireNonBlank(version, "version");
        requireNonBlank(commit, "commit");
        profiles = List.copyOf(Objects.requireNonNull(profiles, "profiles"));
        if (profiles.isEmpty()) {
            throw new IllegalArgumentException("profiles must not be empty");
        }
        for (String profile : profiles) {
            requireNonBlank(profile, "profile");
            if (profile.contains(",")) {
                throw new IllegalArgumentException("profile must not contain ',': " + profile);
            }
        }
    }

    private static void requireNonBlank(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
