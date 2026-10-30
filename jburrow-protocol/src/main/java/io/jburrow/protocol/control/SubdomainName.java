package io.jburrow.protocol.control;

import java.util.Objects;
import java.util.Optional;

/**
 * A tunnel's DNS label under the server's HTTP domain: the rule of {@code tunnels[].subdomain}, 3 to 63 lower-case
 * letters, digits and hyphens, with no leading, trailing or doubled hyphen.
 *
 * <p>Not a payload: the server's route table and the client's options use it so that neither ever holds an unchecked
 * name. Nothing is case-folded here; a caller that reads a host lower-cases it first.
 */
public record SubdomainName(String value) {

    public SubdomainName {
        Objects.requireNonNull(value, "value");
        if (!Fields.SUBDOMAIN.matcher(value).matches()) {
            throw new IllegalArgumentException("Not a valid subdomain label");
        }
    }

    public static Optional<SubdomainName> parse(String label) {
        Objects.requireNonNull(label, "label");
        return Fields.SUBDOMAIN.matcher(label).matches() ? Optional.of(new SubdomainName(label)) : Optional.empty();
    }

    /**
     * The label of {@code host} if it is exactly one valid label under {@code domain}. Both are lower-case and
     * {@code host} has no port; the domain itself and nested labels give nothing.
     */
    public static Optional<SubdomainName> underDomain(String host, String domain) {
        Objects.requireNonNull(host, "host");
        Objects.requireNonNull(domain, "domain");
        int labelLength = host.length() - domain.length() - 1;
        if (labelLength <= 0 || !host.endsWith(domain) || host.charAt(labelLength) != '.') {
            return Optional.empty();
        }
        return parse(host.substring(0, labelLength));
    }

    @Override
    public String toString() {
        return value;
    }
}
