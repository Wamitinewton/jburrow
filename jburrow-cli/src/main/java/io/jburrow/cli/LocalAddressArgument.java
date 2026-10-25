package io.jburrow.cli;

import io.jburrow.client.tunnel.domain.LocalTarget;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import picocli.CommandLine.ITypeConverter;
import picocli.CommandLine.TypeConversionException;

/**
 * Reads {@code [host:]port}: {@code 22} is {@code localhost:22}; a host is a name, an IPv4 address, or an IPv6 address
 * in brackets ({@code [::1]:22}).
 */
final class LocalAddressArgument implements ITypeConverter<LocalTarget> {

    static final String DEFAULT_HOST = "localhost";

    private static final Pattern PORT = Pattern.compile("\\d{1,5}");
    private static final Pattern BRACKETED = Pattern.compile("\\[([^\\[\\]]*)]:(.*)");
    private static final int MAX_PORT = 65_535;

    @Override
    public LocalTarget convert(String value) {
        if (value.contains("://")) {
            throw new TypeConversionException("'" + value + "' is not [host:]port; leave out the scheme");
        }
        String host;
        String port;
        Matcher bracketed = BRACKETED.matcher(value);
        if (bracketed.matches()) {
            host = bracketed.group(1);
            port = bracketed.group(2);
        } else if (value.indexOf(':') < 0) {
            host = DEFAULT_HOST;
            port = value;
        } else if (value.indexOf(':') == value.lastIndexOf(':')) {
            host = value.substring(0, value.indexOf(':'));
            port = value.substring(value.indexOf(':') + 1);
        } else {
            throw new TypeConversionException(
                    "'" + value + "' is not [host:]port; put an IPv6 address in brackets, as in [::1]:22");
        }
        if (host.isEmpty()) {
            throw new TypeConversionException("'" + value + "' has no host before the ':'");
        }
        try {
            return LocalTarget.of(host, port(value, port));
        } catch (IllegalArgumentException e) {
            throw new TypeConversionException(e.getMessage());
        }
    }

    private static int port(String value, String port) {
        if (!PORT.matcher(port).matches() || Integer.parseInt(port) < 1 || Integer.parseInt(port) > MAX_PORT) {
            throw new TypeConversionException("'" + value + "' needs a port between 1 and " + MAX_PORT);
        }
        return Integer.parseInt(port);
    }
}
