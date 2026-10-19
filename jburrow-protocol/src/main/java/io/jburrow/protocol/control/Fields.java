package io.jburrow.protocol.control;

import io.jburrow.protocol.wire.ErrorCode;
import io.jburrow.protocol.wire.FrameLimits;
import io.jburrow.protocol.wire.ProtocolException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * The field rules of {@code docs/protocol.md} §7, shared by the payload records.
 *
 * <p>Every failure is a {@link ErrorCode#VALIDATION_FAILED} session error whose message names the field and the rule
 * and never the value, since a value may be a secret and the message is sent to the peer.
 */
final class Fields {

    static final int MAX_CAPABILITIES = 32;
    static final int MAX_TUNNELS = 32;
    static final int MAX_AUTHTOKEN_LENGTH = 256;
    static final int MAX_ERROR_MESSAGE_LENGTH = 512;
    static final int MAX_HOST_LENGTH = 253;
    static final int MAX_IPV6_LENGTH = 45;
    static final int MIN_PORT = 1;
    static final int MAX_PORT = 65_535;
    static final int MAX_FRAME_PAYLOAD = FrameLimits.MAX_DATA_PAYLOAD;
    static final long MIN_PING_INTERVAL_MS = 1_000;

    static final Pattern PEER_NAME = Pattern.compile("[A-Za-z0-9._-]{1,64}");
    static final Pattern PEER_VERSION = Pattern.compile("[A-Za-z0-9.+_-]{1,64}");
    static final Pattern PLATFORM = Pattern.compile("[a-z0-9_]{1,32}");
    static final Pattern CAPABILITY = Pattern.compile("[a-z0-9.-]{1,64}");
    static final Pattern TUNNEL_REF = Pattern.compile("[a-z0-9][a-z0-9-]{0,31}");

    private static final String OCTET = "(?:25[0-5]|2[0-4][0-9]|1[0-9]{2}|[1-9]?[0-9])";
    private static final Pattern IPV4 = Pattern.compile(OCTET + "(?:\\." + OCTET + "){3}");
    private static final Pattern HEX_GROUP = Pattern.compile("[0-9A-Fa-f]{1,4}");
    private static final String LABEL = "[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?";
    private static final Pattern HOST = Pattern.compile(LABEL + "(?:\\." + LABEL + ")*");

    private static final int IPV6_GROUPS = 8;

    private Fields() {}

    static ProtocolException invalid(String field, String rule) {
        return ProtocolException.session(ErrorCode.VALIDATION_FAILED, field + " " + rule);
    }

    static <T> T required(T value, String field) {
        if (value == null) {
            throw invalid(field, "is required");
        }
        return value;
    }

    static String matching(String value, Pattern pattern, String field) {
        if (!pattern.matcher(required(value, field)).matches()) {
            throw invalid(field, "must match " + pattern.pattern());
        }
        return value;
    }

    static String optionalMatching(String value, Pattern pattern, String field) {
        return value == null ? null : matching(value, pattern, field);
    }

    static String length(String value, int min, int max, String field) {
        int length = required(value, field).length();
        if (length < min || length > max) {
            throw invalid(field, "must be " + min + " to " + max + " characters");
        }
        return value;
    }

    static int range(int value, int min, int max, String field) {
        if (value < min || value > max) {
            throw invalid(field, "must be between " + min + " and " + max);
        }
        return value;
    }

    static Integer optionalRange(Integer value, int min, int max, String field) {
        return value == null ? null : range(value, min, max, field);
    }

    /** An immutable copy of {@code values}, which must hold {@code min} to {@code max} non-null entries. */
    static <T> List<T> list(List<T> values, int min, int max, String field) {
        int size = required(values, field).size();
        if (size < min || size > max) {
            throw invalid(field, "must have " + min + " to " + max + " entries");
        }
        for (int i = 0; i < size; i++) {
            required(values.get(i), field + "[" + i + "]");
        }
        return List.copyOf(values);
    }

    /** A capability list: at most 32 distinct names. */
    static List<String> capabilities(List<String> values, String field) {
        List<String> copy = list(values, 0, MAX_CAPABILITIES, field);
        for (int i = 0; i < copy.size(); i++) {
            matching(copy.get(i), CAPABILITY, field + "[" + i + "]");
        }
        requireUnique(copy, Function.identity(), field, "");
        return copy;
    }

    /** Fails on the first entry whose key repeats an earlier one, naming that entry. */
    static <T> void requireUnique(List<T> values, Function<T, String> key, String field, String keyField) {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < values.size(); i++) {
            if (!seen.add(key.apply(values.get(i)))) {
                throw invalid(field + "[" + i + "]" + keyField, "must be unique");
            }
        }
    }

    /**
     * A host name as the server announces it: a lower-case DNS name or an IPv4 address. An IPv6 literal would need
     * brackets in the tunnel URL, and IPv6 tunnels are not part of this draft.
     */
    static String host(String value, String field) {
        String host = required(value, field);
        if (host.length() > MAX_HOST_LENGTH || !HOST.matcher(host).matches()) {
            throw invalid(field, "must be a lower-case DNS name or IPv4 address of at most 253 characters");
        }
        return host;
    }

    /**
     * An IP address literal: an IPv4 dotted quad, or an IPv6 address without brackets or zone. Checked by its text
     * alone; a host name is never resolved.
     */
    static String ipLiteral(String value, String field) {
        String address = required(value, field);
        if (!IPV4.matcher(address).matches() && !isIpv6Literal(address)) {
            throw invalid(field, "must be an IPv4 or IPv6 address literal");
        }
        return address;
    }

    static boolean isIpv6Literal(String value) {
        if (value.length() > MAX_IPV6_LENGTH) {
            return false;
        }
        int gap = value.indexOf("::");
        if (gap < 0) {
            return groups(value, true) == IPV6_GROUPS;
        }
        if (value.indexOf("::", gap + 1) >= 0) {
            return false;
        }
        int head = groups(value.substring(0, gap), false);
        int tail = groups(value.substring(gap + 2), true);
        return head >= 0 && tail >= 0 && head + tail < IPV6_GROUPS;
    }

    /**
     * The number of 16-bit groups in a colon-separated run of an IPv6 address, or -1 if the run is malformed. A
     * trailing IPv4 address counts as two groups.
     */
    private static int groups(String run, boolean ipv4Allowed) {
        if (run.isEmpty()) {
            return 0;
        }
        String[] parts = run.split(":", -1);
        int groups = 0;
        for (int i = 0; i < parts.length; i++) {
            boolean last = i == parts.length - 1;
            if (last && ipv4Allowed && IPV4.matcher(parts[i]).matches()) {
                groups += 2;
            } else if (HEX_GROUP.matcher(parts[i]).matches()) {
                groups++;
            } else {
                return -1;
            }
        }
        return groups;
    }
}
