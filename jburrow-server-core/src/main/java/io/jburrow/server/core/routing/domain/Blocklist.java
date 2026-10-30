package io.jburrow.server.core.routing.domain;

import io.jburrow.protocol.control.SubdomainName;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Names no tunnel may claim. A reserved name is refused exactly; a blocked term is refused anywhere in a name, which
 * also refuses some honest names ({@code accounting-demo}) because a phishing page on one would put the whole domain at
 * risk.
 */
public final class Blocklist {

    /** The service's own hosts and the names people expect the operator to answer on. */
    static final List<String> RESERVED_NAMES = List.of(
            "abuse",
            "admin",
            "api",
            "app",
            "connect",
            "docs",
            "mail",
            "root",
            "security",
            "status",
            "support",
            "tcp",
            "www");

    /** Words phishing pages put in a host name: sign-in words and the brands most often imitated. */
    static final List<String> BLOCKED_TERMS = List.of(
            "account",
            "amazon",
            "apple",
            "bank",
            "binance",
            "coinbase",
            "facebook",
            "github",
            "gmail",
            "google",
            "icloud",
            "instagram",
            "login",
            "metamask",
            "microsoft",
            "mpesa",
            "netflix",
            "office365",
            "outlook",
            "password",
            "paypal",
            "safaricom",
            "secure",
            "signin",
            "signon",
            "verify",
            "wallet",
            "whatsapp");

    private final Set<String> reservedNames;
    private final List<String> blockedTerms;

    public Blocklist(Set<String> reservedNames, Set<String> blockedTerms) {
        this.reservedNames = Set.copyOf(reservedNames);
        this.blockedTerms = List.copyOf(blockedTerms);
    }

    /** The built-in lists, with {@code extraReserved} (configured names, already lower-case) added to the reserved. */
    public static Blocklist defaults(Set<String> extraReserved) {
        Objects.requireNonNull(extraReserved, "extraReserved");
        Set<String> reserved = new HashSet<>(RESERVED_NAMES);
        reserved.addAll(extraReserved);
        return new Blocklist(reserved, Set.copyOf(BLOCKED_TERMS));
    }

    public boolean allows(SubdomainName name) {
        String value = name.value();
        if (reservedNames.contains(value)) {
            return false;
        }
        for (String term : blockedTerms) {
            if (value.contains(term)) {
                return false;
            }
        }
        return true;
    }
}
