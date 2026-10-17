package io.jburrow.server.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Every {@code jburrow.*} setting. Validated at startup, so a bad value stops the process with its key named. */
@ConfigurationProperties("jburrow")
@Validated
public record JBurrowProperties(
        @Valid @NotNull Api api, @Valid @NotNull Mail mail) {

    /** @param prefix path every JBurrow controller is mapped under, with a leading and no trailing slash */
    public record Api(
            @NotBlank @Pattern(regexp = "^/.*[^/]$", message = "must start with '/' and not end with '/'")
            String prefix) {}

    /**
     * @param fromAddress sender of every outbound message
     * @param fromName display name on every outbound message
     * @param sesConfigurationSet SES configuration set header value; blank means no header
     * @param smokeTestRecipient when set, one test message goes here at startup
     */
    public record Mail(
            @NotBlank @Email String fromAddress,
            @NotBlank String fromName,
            String sesConfigurationSet,
            @Email String smokeTestRecipient) {}
}
