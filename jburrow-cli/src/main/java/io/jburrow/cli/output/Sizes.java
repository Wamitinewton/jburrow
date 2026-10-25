package io.jburrow.cli.output;

import java.util.Locale;

/** Byte counts, durations and addresses as both outputs print them: {@code 3.1 KB}, {@code 2m37s}. */
final class Sizes {

    private static final String[] UNITS = {"KB", "MB", "GB", "TB", "PB", "EB"};
    private static final double DECIMAL = 1000.0;
    /** The smallest value that prints as {@code 1000.0} with one decimal, so it moves to the next unit instead. */
    private static final double ROUNDS_UP = 999.95;

    private static final long SECOND = 1000;
    private static final long MINUTE = 60 * SECOND;
    private static final long HOUR = 60 * MINUTE;

    private Sizes() {}

    /** Decimal units with one decimal, {@code 41.0 KB}; under 1,000 bytes, {@code 512 B}. */
    static String bytes(long count) {
        if (count < DECIMAL) {
            return count + " B";
        }
        double value = count / DECIMAL;
        int unit = 0;
        while (value >= ROUNDS_UP && unit < UNITS.length - 1) {
            value /= DECIMAL;
            unit++;
        }
        return String.format(Locale.ROOT, "%.1f %s", value, UNITS[unit]);
    }

    /** {@code 850ms}, {@code 42s}, {@code 2m37s}, {@code 1h04m}; each unit truncated, never rounded up. */
    static String duration(long millis) {
        long ms = Math.max(0, millis);
        if (ms < SECOND) {
            return ms + "ms";
        }
        if (ms < MINUTE) {
            return ms / SECOND + "s";
        }
        if (ms < HOUR) {
            return String.format(Locale.ROOT, "%dm%02ds", ms / MINUTE, ms % MINUTE / SECOND);
        }
        return String.format(Locale.ROOT, "%dh%02dm", ms / HOUR, ms % HOUR / MINUTE);
    }

    /** {@code host:port}, with an IPv6 address in brackets. */
    static String endpoint(String host, int port) {
        return (host.indexOf(':') >= 0 ? "[" + host + "]" : host) + ":" + port;
    }
}
