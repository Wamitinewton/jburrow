package io.jburrow.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.jburrow.client.tunnel.domain.LocalTarget;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import picocli.CommandLine.TypeConversionException;

class LocalAddressArgumentTest {

    private final LocalAddressArgument argument = new LocalAddressArgument();

    @ParameterizedTest(name = "{0} is {1} port {2}")
    @CsvSource({
        "22, localhost, 22",
        "localhost:22, localhost, 22",
        "192.168.1.5:22, 192.168.1.5, 22",
        "[::1]:22, ::1, 22",
        "[2001:db8::7]:8080, 2001:db8::7, 8080",
        "db.internal:5432, db.internal, 5432",
        "1, localhost, 1",
        "65535, localhost, 65535"
    })
    void readsHostAndPort(String value, String host, int port) {
        assertThat(argument.convert(value)).isEqualTo(LocalTarget.of(host, port));
    }

    @ParameterizedTest(name = "{0} is refused")
    @ValueSource(
            strings = {
                "0",
                "70000",
                "65536",
                "-1",
                "+22",
                "ssh",
                "",
                "host:",
                ":22",
                "[]:22",
                "::1:22",
                "tcp://localhost:22",
                "localhost:22/path",
                "[::1]22",
                "local host:22"
            })
    void refusesWhatIsNotHostAndPort(String value) {
        assertThatThrownBy(() -> argument.convert(value)).isInstanceOf(TypeConversionException.class);
    }

    @ParameterizedTest(name = "{0} says why")
    @CsvSource(
            delimiter = '|',
            value = {
                "70000|needs a port between 1 and 65535",
                "tcp://localhost:22|leave out the scheme",
                "::1:22|put an IPv6 address in brackets",
                ":22|has no host"
            })
    void refusalsSayWhatToChange(String value, String reason) {
        assertThatThrownBy(() -> argument.convert(value)).hasMessageContaining(reason);
    }
}
