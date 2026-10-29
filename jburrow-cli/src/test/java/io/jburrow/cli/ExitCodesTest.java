package io.jburrow.cli;

import static io.jburrow.cli.ExitCodes.AUTHENTICATION_FAILED;
import static io.jburrow.cli.ExitCodes.CONNECTION_FAILED;
import static io.jburrow.cli.ExitCodes.INTERNAL_ERROR;
import static io.jburrow.cli.ExitCodes.PROTOCOL_MISMATCH;
import static io.jburrow.cli.ExitCodes.REGISTRATION_REFUSED;
import static io.jburrow.cli.ExitCodes.USAGE;
import static io.jburrow.cli.ExitCodes.USER_CLOSED;
import static org.assertj.core.api.Assertions.assertThat;

import io.jburrow.client.TunnelStartException;
import io.jburrow.client.TunnelStartException.Kind;
import io.jburrow.client.tunnel.domain.TunnelClosed;
import io.jburrow.protocol.wire.ErrorCode;
import java.io.PrintWriter;
import java.io.Writer;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import picocli.CommandLine;
import picocli.CommandLine.ParameterException;

/** Phase 1 ARCHITECTURE.md §The CLI's exit code table, row for row, and phase 2's {@code FLOW_CONTROL_ERROR}. */
class ExitCodesTest {

    @ParameterizedTest(name = "{0} exits {1}")
    @CsvSource({
        "AUTHTOKEN_INVALID, 3",
        "NOT_FOUND, 4",
        "SERVER_AT_CAPACITY, 4",
        "SERVER_SHUTTING_DOWN, 4",
        "HANDSHAKE_TIMEOUT, 4",
        "HEARTBEAT_TIMEOUT, 4",
        "INTERNAL_ERROR, 4",
        "VALIDATION_FAILED, 5",
        "TUNNEL_LIMIT_EXCEEDED, 5",
        "SESSION_LIMIT_EXCEEDED, 5",
        "PORT_UNAVAILABLE, 5",
        "PORT_POOL_EXHAUSTED, 5",
        "SUBDOMAIN_UNAVAILABLE, 5",
        "SUBDOMAIN_FORBIDDEN, 5",
        "UNSUPPORTED_PROTOCOL_VERSION, 6",
        "PROTOCOL_ERROR, 6",
        "FRAME_SIZE_ERROR, 6",
        "FLOW_CONTROL_ERROR, 6",
        "FORBIDDEN_ORIGIN, 6",
        "PING_RATE_EXCEEDED, 6",
        "PEER_RESET, 1",
        "LOCAL_SERVICE_UNREACHABLE, 1",
        "LOCAL_SERVICE_ERROR, 1",
        "STREAM_CLOSED, 1",
        "CANCELLED, 1"
    })
    void everyErrorCodeHasItsExitCode(ErrorCode code, int exitCode) {
        assertThat(ExitCodes.of(code)).isEqualTo(exitCode);
    }

    @ParameterizedTest(name = "{0} is the same at start and at close")
    @EnumSource(
            value = ErrorCode.class,
            names = {"AUTHTOKEN_INVALID", "PROTOCOL_ERROR", "SERVER_SHUTTING_DOWN", "PORT_UNAVAILABLE"})
    void aCodeMeansTheSameWhetherTheSessionFailedToStartOrEnded(ErrorCode code) {
        TunnelStartException start =
                new TunnelStartException(Kind.SERVER_ERROR, "refused", Optional.of(code), List.of(), null);

        assertThat(ExitCodes.of(start)).isEqualTo(ExitCodes.of(code));
        assertThat(ExitCodes.of(TunnelClosed.serverError(code, "ended"))).isEqualTo(ExitCodes.of(code));
        assertThat(ExitCodes.of(TunnelClosed.localError(code, "ended"))).isEqualTo(ExitCodes.of(code));
    }

    @ParameterizedTest(name = "{0} without a code exits {1}")
    @CsvSource({
        "CONNECT_FAILED, 4",
        "TLS_FAILED, 4",
        "TIMEOUT, 4",
        "UPGRADE_REFUSED, 4",
        "SERVER_ERROR, 4",
        "LOCAL_ERROR, 1"
    })
    void aStartFailureWithoutACodeExitsByItsKind(Kind kind, int exitCode) {
        assertThat(ExitCodes.of(new TunnelStartException(kind, "failed"))).isEqualTo(exitCode);
    }

    @Test
    void howTheSessionEndedDecidesTheExitCode() {
        assertThat(ExitCodes.of(TunnelClosed.userClosed())).isEqualTo(USER_CLOSED);
        assertThat(ExitCodes.of(TunnelClosed.connectionLost("gone"))).isEqualTo(CONNECTION_FAILED);
        assertThat(ExitCodes.of(TunnelClosed.localError(ErrorCode.HEARTBEAT_TIMEOUT, "silent")))
                .isEqualTo(CONNECTION_FAILED);
        assertThat(ExitCodes.of(TunnelClosed.serverError(ErrorCode.AUTHTOKEN_INVALID, "revoked")))
                .isEqualTo(AUTHENTICATION_FAILED);
        assertThat(ExitCodes.of(TunnelClosed.serverError(ErrorCode.TUNNEL_LIMIT_EXCEEDED, "limit")))
                .isEqualTo(REGISTRATION_REFUSED);
        assertThat(ExitCodes.of(TunnelClosed.localError(ErrorCode.FRAME_SIZE_ERROR, "too big")))
                .isEqualTo(PROTOCOL_MISMATCH);
    }

    @Test
    void usageErrorsExitTwoAndAnythingElseThrownExitsOne() {
        ParameterException usage = new ParameterException(JBurrowCli.commandLine(), "bad");

        assertThat(ExitCodes.EXCEPTIONS.getExitCode(usage)).isEqualTo(USAGE);
        assertThat(ExitCodes.EXCEPTIONS.getExitCode(new IllegalStateException("bug")))
                .isEqualTo(INTERNAL_ERROR);
        assertThat(JBurrowCli.commandLine().getExitCodeExceptionMapper()).isSameAs(ExitCodes.EXCEPTIONS);
    }

    @Test
    void anUnknownSubcommandArgumentExitsTwo() {
        CommandLine cli = JBurrowCli.commandLine();
        cli.setErr(new PrintWriter(Writer.nullWriter()));

        assertThat(cli.execute("tcp", "22", "--no-such-option")).isEqualTo(USAGE);
    }
}
