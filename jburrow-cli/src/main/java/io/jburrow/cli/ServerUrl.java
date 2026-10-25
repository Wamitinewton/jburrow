package io.jburrow.cli;

import java.net.URI;
import java.net.URISyntaxException;
import picocli.CommandLine.ITypeConverter;
import picocli.CommandLine.TypeConversionException;

/**
 * Reads {@code --server} as a URI. Only the syntax is checked here; the connect URL rules ({@code wss://}, or
 * {@code ws://} to this machine, and the path) are the client's, and {@code JBurrowClient.Builder.build()} reports
 * them.
 */
final class ServerUrl implements ITypeConverter<URI> {

    static final String DEFAULT = "wss://connect.cloudnativehub.app/v1/connect";
    static final String ENVIRONMENT_VARIABLE = "JBURROW_SERVER";

    @Override
    public URI convert(String value) {
        try {
            URI uri = new URI(value.strip());
            if (!uri.isAbsolute() || uri.getHost() == null) {
                throw new TypeConversionException(
                        "'" + value + "' is not a server URL; expected wss://<host>/v1/connect");
            }
            return uri;
        } catch (URISyntaxException e) {
            throw new TypeConversionException("'" + value + "' is not a valid URL: " + e.getReason());
        }
    }
}
