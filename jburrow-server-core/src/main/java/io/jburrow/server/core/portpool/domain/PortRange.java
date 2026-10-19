package io.jburrow.server.core.portpool.domain;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** An inclusive range of TCP ports that tunnels are bound in, written {@code 20000-20999}. */
public record PortRange(int first, int last) {

    public static final int MIN_PORT = 1024;
    public static final int MAX_PORT = 65_535;

    private static final Pattern TEXT = Pattern.compile("(\\d{1,5})-(\\d{1,5})");

    public PortRange {
        if (first < MIN_PORT || last > MAX_PORT) {
            throw new IllegalArgumentException(
                    "A port range must lie within " + MIN_PORT + "-" + MAX_PORT + ": " + first + "-" + last);
        }
        if (first > last) {
            throw new IllegalArgumentException("A port range must not end before it starts: " + first + "-" + last);
        }
    }

    /** Parses {@code <first>-<last>}, with no spaces. */
    public static PortRange parse(String text) {
        if (text == null) {
            throw new IllegalArgumentException("A port range is required");
        }
        Matcher matcher = TEXT.matcher(text);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("A port range must be written <first>-<last>: " + text);
        }
        return new PortRange(Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2)));
    }

    public boolean contains(int port) {
        return port >= first && port <= last;
    }

    public int size() {
        return last - first + 1;
    }

    @Override
    public String toString() {
        return first + "-" + last;
    }
}
