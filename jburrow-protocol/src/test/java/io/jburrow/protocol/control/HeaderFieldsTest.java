package io.jburrow.protocol.control;

import static io.jburrow.protocol.control.ValidationAssertions.assertInvalid;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class HeaderFieldsTest {

    private static final String NAME_RULE = "name must match [a-z0-9!#$%&'*+.^_`|~-]{1,256}";
    private static final String VALUE_CHARACTERS = "value must hold only HTAB, U+0020 to U+007E and U+0080 to U+00FF";
    private static final String VALUE_ENDS = "value must not start or end with whitespace";

    @ParameterizedTest
    @ValueSource(strings = {"host", "x-forwarded-for", "a", "!#$%&'*+-.^_`|~", "0", "www-authenticate"})
    void namesAreLowerCaseTokens(String name) {
        new HeaderFields.Field(name, "v");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "Host", "X-Forwarded-For", "a b", "a:b", "a\"b", "a(b", "a/b", "a@b", "aé", "a\tb"})
    void namesThatAreNotLowerCaseTokensAreRefused(String name) {
        assertInvalid(() -> new HeaderFields.Field(name, "v"), NAME_RULE);
    }

    @Test
    void namesAreOneTo256Characters() {
        new HeaderFields.Field("a".repeat(256), "v");
        assertInvalid(() -> new HeaderFields.Field("a".repeat(257), "v"), NAME_RULE);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "text/html", "a\tb", "a b", "~", "café", "\u0080", "ÿ", "\"quoted\""})
    void valuesAreIso88591TextWithoutControlCharacters(String value) {
        assertThat(new HeaderFields.Field("x", value).value()).isEqualTo(value);
    }

    @ParameterizedTest
    @ValueSource(strings = {"a\rb", "a\nb", "a\r\nb", "a\u0000b", "a\u0001b", "a\u007fb", "aĀb", "€", "😀"})
    void controlCharactersAndCodePointsPastU00ffAreRefused(String value) {
        assertInvalid(() -> new HeaderFields.Field("x", value), VALUE_CHARACTERS);
    }

    @ParameterizedTest
    @ValueSource(strings = {" a", "a ", "\ta", "a\t", " ", "\t"})
    void valuesDoNotStartOrEndWithWhitespace(String value) {
        assertInvalid(() -> new HeaderFields.Field("x", value), VALUE_ENDS);
    }

    @Test
    void valuesAreAtMost4096Characters() {
        new HeaderFields.Field("x", "v".repeat(4_096));
        assertInvalid(() -> new HeaderFields.Field("x", "v".repeat(4_097)), "value must be at most 4096 characters");
    }

    @Test
    void nameAndValueAreRequired() {
        assertInvalid(() -> new HeaderFields.Field(null, "v"), "name is required");
        assertInvalid(() -> new HeaderFields.Field("x", null), "value is required");
    }

    static List<String> connectionSpecific() {
        return List.of("connection", "keep-alive", "proxy-connection", "transfer-encoding", "te", "trailer", "upgrade");
    }

    @ParameterizedTest
    @MethodSource("connectionSpecific")
    void connectionSpecificFieldsAreRefused(String name) {
        assertThat(HeaderFields.CONNECTION_SPECIFIC).contains(name);
        assertInvalid(
                () -> HeaderFields.of("host", "example.dev", name, "x"),
                "[1] name must not be a connection-specific field");
    }

    @Test
    void connectionSpecificFieldsAreExactlyTheSpecifications() {
        assertThat(HeaderFields.CONNECTION_SPECIFIC).containsExactlyInAnyOrderElementsOf(connectionSpecific());
    }

    @Test
    void keepsOrderAndRepeats() {
        HeaderFields headers =
                HeaderFields.of("set-cookie", "a=1", "location", "/", "set-cookie", "b=2", "set-cookie", "a=1");

        assertThat(headers.fields())
                .extracting(HeaderFields.Field::name)
                .containsExactly("set-cookie", "location", "set-cookie", "set-cookie");
        assertThat(headers.all("set-cookie")).containsExactly("a=1", "b=2", "a=1");
        assertThat(headers.all("cookie")).isEmpty();
    }

    @Test
    void namesCompareExactly() {
        assertThat(HeaderFields.of("host", "a").all("Host")).isEmpty();
    }

    @Test
    void singleIsTheOneValueOrNothing() {
        HeaderFields headers = HeaderFields.of("host", "a", "accept", "x", "accept", "y");

        assertThat(headers.single("host")).contains("a");
        assertThat(headers.single("cookie")).isEmpty();
        assertInvalid(() -> headers.single("accept"), "headers must not repeat accept");
    }

    @Test
    void anEmptyListIsAllowed() {
        assertThat(new HeaderFields(List.of()).fields()).isEmpty();
    }

    @Test
    void atMost256Fields() {
        List<HeaderFields.Field> fields = new ArrayList<>();
        for (int i = 0; i < HeaderFields.MAX_FIELDS; i++) {
            fields.add(new HeaderFields.Field("x", "v"));
        }
        assertThat(new HeaderFields(fields).fields()).hasSize(256);

        fields.add(new HeaderFields.Field("x", "v"));
        assertInvalid(() -> new HeaderFields(fields), "[256] is past the limit of 256 fields");
    }

    @Test
    void fieldsAndEachEntryAreRequired() {
        assertInvalid(() -> new HeaderFields(null), "fields is required");
        List<HeaderFields.Field> withNull = new ArrayList<>();
        withNull.add(null);
        assertInvalid(() -> new HeaderFields(withNull), "[0] is required");
    }

    @Test
    void theListIsACopy() {
        List<HeaderFields.Field> fields = new ArrayList<>(List.of(new HeaderFields.Field("x", "v")));
        HeaderFields headers = new HeaderFields(fields);
        fields.clear();

        assertThat(headers.fields()).hasSize(1);
    }

    @Test
    void ofTakesPairs() {
        assertThatIllegalArgumentException().isThrownBy(() -> HeaderFields.of("host"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "1043", "0001", "9007199254740991"})
    void contentLengthIsDecimalDigitsUpTo2Pow53Minus1(String value) {
        assertThat(HeaderFields.of("content-length", value).contentLength())
                .isEqualTo(OptionalLong.of(Long.parseLong(value)));
    }

    @Test
    void contentLengthIsAbsentWithoutTheField() {
        assertThat(HeaderFields.of("host", "a").contentLength()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "-1", "+1", "1.0", "1e3", "0x10", "1,2", "12345678901234567", "a"})
    void contentLengthThatIsNotDigitsIsRefused(String value) {
        assertInvalid(
                () -> HeaderFields.of("content-length", value).contentLength(),
                "headers content-length must be 1 to 16 decimal digits");
    }

    @Test
    void contentLengthPast2Pow53Minus1IsRefused() {
        assertInvalid(
                () -> HeaderFields.of("content-length", "9007199254740992").contentLength(),
                "headers content-length must be at most 2^53 - 1");
    }

    @Test
    void contentLengthIsNeverRepeated() {
        assertInvalid(
                () -> HeaderFields.of("content-length", "5", "content-length", "5")
                        .contentLength(),
                "headers must not repeat content-length");
    }
}
