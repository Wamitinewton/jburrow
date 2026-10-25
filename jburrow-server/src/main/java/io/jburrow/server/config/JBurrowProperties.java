package io.jburrow.server.config;

import io.jburrow.server.core.portpool.domain.PortRange;
import io.jburrow.server.core.tunnel.adapter.netty.edge.EdgeTransport;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;
import org.springframework.validation.annotation.Validated;

/**
 * Every {@code jburrow.*} setting. Validated at startup, so a bad value stops the process with its key named.
 *
 * <p>Rules across fields are {@code @AssertTrue} methods whose message names the key, since the binder reports them
 * under the method's name. Each returns true when a field it reads is missing, which that field's own constraint
 * reports.
 */
@ConfigurationProperties("jburrow")
@Validated
public record JBurrowProperties(
        @Valid @NotNull Api api,
        @Valid @NotNull Mail mail,
        @Valid @NotNull @DefaultValue Auth auth,
        @Valid @NotNull @DefaultValue Edge edge,
        @Valid @NotNull @DefaultValue Tcp tcp) {

    @AssertTrue(message = "jburrow.tcp.port-range must not contain jburrow.edge.port")
    public boolean isTcpPortRangeClearOfTheEdge() {
        if (edge == null || tcp == null) {
            return true;
        }
        return tcp.parsedPortRange().map(range -> !range.contains(edge.port())).orElse(true);
    }

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

    /**
     * @param staticTokens {@code name:sha256hex} entries, the hash being the SHA-256 of the full token; empty means
     *     every {@code REGISTER} is refused. Bound from a comma-separated {@code JBURROW_AUTH_STATIC_TOKENS}. Each
     *     entry is checked by {@code AuthConfiguration} rather than a {@code @Pattern}: a bind failure prints the
     *     rejected value, and an operator who pasted a token where its entry belongs would see it in the logs.
     */
    public record Auth(@NotNull @DefaultValue List<String> staticTokens) {}

    /**
     * The edge listener: the control WebSocket, its caps and its timings.
     *
     * @param port 0 picks an ephemeral port, for tests
     * @param bindAddress an IP literal; a name is never resolved
     * @param connectHost the only {@code Host} the edge answers
     * @param ioThreads worker event loops; absent means the number of processors, at least 2
     * @param trustedProxies CIDRs whose {@code X-Forwarded-For} is believed, checked by {@code EdgeConfiguration}
     * @param requestTimeout how long a connection may take to send its upgrade request
     * @param handshakeTimeout how long a session may take from the upgrade to its {@code REGISTER} reply
     * @param sessionWriteBuffer low and high write-buffer watermarks of a session channel, {@code 128KB-512KB}
     * @param visitorWriteBuffer low and high write-buffer watermarks of a visitor channel, {@code 32KB-128KB}
     */
    public record Edge(
            @Min(0) @Max(65_535) @DefaultValue("7080") int port,
            @NotBlank @DefaultValue("0.0.0.0") String bindAddress,
            @NotBlank String connectHost,
            @NotNull @DefaultValue("auto") EdgeTransport transport,
            @Positive Integer ioThreads,

            @NotNull @DefaultValue({"127.0.0.1/32", "::1/128"})
            List<String> trustedProxies,

            @Positive @DefaultValue("200") int maxSessions,
            @Positive @DefaultValue("64") int maxHandshaking,
            @Positive @DefaultValue("3") int maxSessionsPerPrincipal,
            @NotNull @DefaultValue("10s") Duration requestTimeout,
            @NotNull @DefaultValue("10s") Duration handshakeTimeout,
            @NotNull @DefaultValue("15s") Duration pingInterval,
            @NotNull @DefaultValue("45s") Duration idleTimeout,
            @NotBlank @DefaultValue("128KB-512KB") String sessionWriteBuffer,
            @NotBlank @DefaultValue("32KB-128KB") String visitorWriteBuffer) {

        /** The protocol's floor for the announced ping interval ({@code docs/protocol.md} §10). */
        static final Duration MIN_PING_INTERVAL = Duration.ofSeconds(1);

        private static final int MIN_IO_THREADS = 2;

        /** {@link #ioThreads}, or the number of processors with a floor of 2. */
        public int effectiveIoThreads() {
            return ioThreads != null
                    ? ioThreads
                    : Math.max(MIN_IO_THREADS, Runtime.getRuntime().availableProcessors());
        }

        @AssertTrue(message = "jburrow.edge.request-timeout must be positive")
        public boolean isRequestTimeoutValid() {
            return requestTimeout == null || isPositive(requestTimeout);
        }

        @AssertTrue(message = "jburrow.edge.handshake-timeout must be positive")
        public boolean isHandshakeTimeoutValid() {
            return handshakeTimeout == null || isPositive(handshakeTimeout);
        }

        @AssertTrue(message = "jburrow.edge.ping-interval must be at least 1s")
        public boolean isPingIntervalValid() {
            return pingInterval == null || pingInterval.compareTo(MIN_PING_INTERVAL) >= 0;
        }

        @AssertTrue(message = "jburrow.edge.idle-timeout must be at least twice jburrow.edge.ping-interval")
        public boolean isIdleTimeoutValid() {
            return idleTimeout == null
                    || pingInterval == null
                    || idleTimeout.compareTo(pingInterval.multipliedBy(2)) >= 0;
        }

        @AssertTrue(message = "jburrow.edge.max-handshaking must not exceed jburrow.edge.max-sessions")
        public boolean isMaxHandshakingValid() {
            return maxHandshaking <= maxSessions;
        }

        @AssertTrue(
                message = "jburrow.edge.session-write-buffer must be <low>-<high> sizes with low below high, such as"
                        + " 128KB-512KB")
        public boolean isSessionWriteBufferValid() {
            return sessionWriteBuffer == null
                    || Watermarks.parse(sessionWriteBuffer).isPresent();
        }

        @AssertTrue(
                message = "jburrow.edge.visitor-write-buffer must be <low>-<high> sizes with low below high, such as"
                        + " 32KB-128KB")
        public boolean isVisitorWriteBufferValid() {
            return visitorWriteBuffer == null
                    || Watermarks.parse(visitorWriteBuffer).isPresent();
        }

        public Watermarks sessionWatermarks() {
            return Watermarks.parse(sessionWriteBuffer).orElseThrow();
        }

        public Watermarks visitorWatermarks() {
            return Watermarks.parse(visitorWriteBuffer).orElseThrow();
        }

        private static boolean isPositive(Duration duration) {
            return !duration.isNegative() && !duration.isZero();
        }
    }

    /**
     * TCP tunnels.
     *
     * @param portRange the ports tunnels are bound in, {@code <first>-<last>} within {@code 1024-65535}
     * @param bindAddress an IP literal tunnel listeners bind to; a name is never resolved
     * @param publicHost the host name tunnel addresses are given under
     * @param reuseDelay how long a released port rests before it is handed to someone else
     */
    public record Tcp(
            @NotBlank String portRange,
            @NotBlank @DefaultValue("0.0.0.0") String bindAddress,
            @NotBlank String publicHost,
            @NotNull @DefaultValue("60s") Duration reuseDelay) {

        @AssertTrue(message = "jburrow.tcp.port-range must be <first>-<last> within 1024-65535, such as 20000-20999")
        public boolean isPortRangeValid() {
            return portRange == null || parsedPortRange().isPresent();
        }

        @AssertTrue(message = "jburrow.tcp.reuse-delay must not be negative")
        public boolean isReuseDelayValid() {
            return reuseDelay == null || !reuseDelay.isNegative();
        }

        /** The port range, or empty if it is missing or malformed. */
        public Optional<PortRange> parsedPortRange() {
            if (portRange == null) {
                return Optional.empty();
            }
            try {
                return Optional.of(PortRange.parse(portRange));
            } catch (IllegalArgumentException e) {
                return Optional.empty();
            }
        }
    }

    /** A low and a high write-buffer watermark, in bytes, written {@code 128KB-512KB}. */
    public record Watermarks(int low, int high) {

        /** Each half is a Spring {@link DataSize}; empty unless both parse, fit an int, and {@code 0 < low < high}. */
        static Optional<Watermarks> parse(String text) {
            String[] halves = text.split("-", -1);
            if (halves.length != 2) {
                return Optional.empty();
            }
            try {
                long low = DataSize.parse(halves[0].strip()).toBytes();
                long high = DataSize.parse(halves[1].strip()).toBytes();
                if (low < 1 || low >= high || high > Integer.MAX_VALUE) {
                    return Optional.empty();
                }
                return Optional.of(new Watermarks((int) low, (int) high));
            } catch (IllegalArgumentException e) {
                return Optional.empty();
            }
        }
    }
}
