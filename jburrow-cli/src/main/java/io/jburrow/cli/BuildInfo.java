package io.jburrow.cli;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Properties;
import picocli.CommandLine.IVersionProvider;

/**
 * Version and commit of this CLI build, read from {@code jburrow-cli.properties} (filtered by Maven) and
 * {@code git.properties}. Either falls back to {@code unknown} when run from an IDE without a Maven build.
 */
public final class BuildInfo implements IVersionProvider {

    static final String UNKNOWN = "unknown";

    private final String version;
    private final String commit;

    public BuildInfo() {
        this(load("/jburrow-cli.properties"), load("/git.properties"));
    }

    BuildInfo(Properties build, Properties git) {
        this.version = valueOrUnknown(build.getProperty("version"));
        this.commit = valueOrUnknown(git.getProperty("git.commit.id.abbrev"));
    }

    public String version() {
        return version;
    }

    public String commit() {
        return commit;
    }

    /** The line {@code --version} and {@code jburrow version} both print. */
    public String describe() {
        return "jburrow " + version + " (" + commit + ")";
    }

    @Override
    public String[] getVersion() {
        return new String[] {describe()};
    }

    private static String valueOrUnknown(String value) {
        return value == null || value.isBlank() || value.startsWith("@") ? UNKNOWN : value;
    }

    private static Properties load(String resource) {
        Properties properties = new Properties();
        try (InputStream in = BuildInfo.class.getResourceAsStream(resource)) {
            if (in != null) {
                properties.load(in);
            }
        } catch (IOException ex) {
            throw new UncheckedIOException("cannot read " + resource, ex);
        }
        return properties;
    }
}
