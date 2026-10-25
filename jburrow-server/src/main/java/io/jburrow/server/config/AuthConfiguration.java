package io.jburrow.server.config;

import io.jburrow.server.api.token.adapter.staticconfig.StaticAuthtokenVerifier;
import io.jburrow.server.api.token.adapter.staticconfig.StaticTokenEntry;
import io.jburrow.server.core.tunnel.application.port.AuthtokenVerifier;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wires the {@code AuthtokenVerifier} port to phase 1's static tokens from {@code jburrow.auth.static-tokens}. */
@Configuration(proxyBeanMethods = false)
class AuthConfiguration {

    private static final Logger log = LoggerFactory.getLogger(AuthConfiguration.class);

    private static final String KEY = "jburrow.auth.static-tokens (JBURROW_AUTH_STATIC_TOKENS)";

    @Bean
    AuthtokenVerifier authtokenVerifier(JBurrowProperties properties) {
        StaticAuthtokenVerifier verifier = staticVerifier(properties.auth().staticTokens());
        if (verifier.size() == 0) {
            log.warn("No static authtokens configured; every REGISTER will be refused");
        } else {
            log.info("Loaded {} static authtokens", verifier.size());
        }
        return verifier;
    }

    /**
     * Parses every entry and stops startup on the first bad one, naming the key and the entry's position. The value is
     * never repeated, since it may be a token pasted where its hash belongs.
     */
    static StaticAuthtokenVerifier staticVerifier(List<String> entries) {
        List<StaticTokenEntry> parsed = new ArrayList<>(entries.size());
        for (int i = 0; i < entries.size(); i++) {
            try {
                parsed.add(StaticTokenEntry.parse(entries.get(i)));
            } catch (IllegalArgumentException e) {
                throw invalid("entry " + (i + 1) + ": " + e.getMessage(), e);
            }
        }
        try {
            return new StaticAuthtokenVerifier(parsed);
        } catch (IllegalArgumentException e) {
            throw invalid(e.getMessage(), e);
        }
    }

    private static IllegalStateException invalid(String reason, IllegalArgumentException cause) {
        return new IllegalStateException("Invalid " + KEY + ", " + reason, cause);
    }
}
