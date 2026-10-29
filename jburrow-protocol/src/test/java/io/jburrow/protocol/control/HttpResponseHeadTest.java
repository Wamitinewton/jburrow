package io.jburrow.protocol.control;

import static io.jburrow.protocol.control.ValidationAssertions.assertInvalid;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class HttpResponseHeadTest {

    private static final HeaderFields NONE = HeaderFields.of();
    private static final HeaderFields LENGTH_5 = HeaderFields.of("content-length", "5");

    @Test
    void acceptsTheArchitectureExample() {
        HttpResponseHead head = new HttpResponseHead(
                200,
                HeaderFields.of("content-type", "text/html; charset=utf-8", "content-length", "1043"),
                BodyFraming.LENGTH);

        assertThat(head.informational()).isFalse();
    }

    @ParameterizedTest
    @ValueSource(ints = {100, 102, 103, 199})
    void informationalHeadsHaveNoBody(int status) {
        HttpResponseHead head = new HttpResponseHead(status, HeaderFields.of("link", "</a.css>"), null);

        assertThat(head.informational()).isTrue();
        assertInvalid(
                () -> new HttpResponseHead(status, NONE, BodyFraming.EMPTY), "body must be absent for a 1xx status");
    }

    @ParameterizedTest
    @ValueSource(ints = {200, 201, 299, 404, 500, 599})
    void finalHeadsHaveABody(int status) {
        assertThat(new HttpResponseHead(status, NONE, BodyFraming.CHUNKED).informational())
                .isFalse();
        assertInvalid(() -> new HttpResponseHead(status, NONE, null), "body is required");
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 0, 99, 600, 1000})
    void statusIs100To599(int status) {
        assertInvalid(
                () -> new HttpResponseHead(status, NONE, BodyFraming.EMPTY), "status must be between 100 and 599");
    }

    @Test
    void switchingProtocolsIsReserved() {
        assertInvalid(() -> new HttpResponseHead(101, NONE, null), "status must not be 101, which is reserved");
    }

    @Test
    void emptyAllowsAnyContentLength() {
        new HttpResponseHead(200, NONE, BodyFraming.EMPTY);
        new HttpResponseHead(200, LENGTH_5, BodyFraming.EMPTY);
        new HttpResponseHead(304, HeaderFields.of("content-length", "1043"), BodyFraming.EMPTY);
    }

    @Test
    void lengthNeedsOneContentLength() {
        new HttpResponseHead(200, LENGTH_5, BodyFraming.LENGTH);
        assertInvalid(
                () -> new HttpResponseHead(200, NONE, BodyFraming.LENGTH),
                "body must not be length without a content-length field");
        assertInvalid(
                () -> new HttpResponseHead(
                        200, HeaderFields.of("content-length", "5", "content-length", "6"), BodyFraming.LENGTH),
                "headers must not repeat content-length");
    }

    @Test
    void chunkedHasNoContentLength() {
        assertInvalid(
                () -> new HttpResponseHead(200, LENGTH_5, BodyFraming.CHUNKED),
                "body must not be chunked with a content-length field");
    }

    @Test
    void contentLengthMustBeWellFormedWhateverTheBody() {
        assertInvalid(
                () -> new HttpResponseHead(200, HeaderFields.of("content-length", "x"), BodyFraming.EMPTY),
                "headers content-length must be 1 to 16 decimal digits");
        assertInvalid(
                () -> new HttpResponseHead(103, HeaderFields.of("content-length", "x"), null),
                "headers content-length must be 1 to 16 decimal digits");
    }

    @ParameterizedTest
    @ValueSource(ints = {204, 304})
    void noContentAndNotModifiedAreAlwaysEmpty(int status) {
        new HttpResponseHead(status, NONE, BodyFraming.EMPTY);
        assertInvalid(
                () -> new HttpResponseHead(status, NONE, BodyFraming.CHUNKED),
                "body must be empty for status " + status);
        assertInvalid(
                () -> new HttpResponseHead(status, LENGTH_5, BodyFraming.LENGTH),
                "body must be empty for status " + status);
    }

    @Test
    void headersAreRequired() {
        assertInvalid(() -> new HttpResponseHead(200, null, BodyFraming.EMPTY), "headers is required");
    }

    @Test
    void connectionSpecificFieldsNeverReachAHead() {
        assertInvalid(
                () -> new HttpResponseHead(200, HeaderFields.of("transfer-encoding", "chunked"), BodyFraming.CHUNKED),
                "[0] name must not be a connection-specific field");
    }
}
