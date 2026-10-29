package io.jburrow.protocol.stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import io.jburrow.protocol.control.BodyFraming;
import io.jburrow.protocol.control.HeaderFields;
import io.jburrow.protocol.control.HttpRequestHead;
import io.jburrow.protocol.control.HttpResponseHead;
import io.jburrow.protocol.wire.ErrorCode;
import io.jburrow.protocol.wire.ErrorScope;
import io.jburrow.protocol.wire.ProtocolException;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** The rows of {@code docs/protocol.md} §9.7's violations that the heads alone can't check. */
class HttpExchangeRulesTest {

    private static final HttpRequestHead GET =
            request("GET", HeaderFields.of("host", "x.example.dev"), BodyFraming.EMPTY);
    private static final HttpRequestHead POST_5 =
            request("POST", HeaderFields.of("host", "x.example.dev", "content-length", "5"), BodyFraming.LENGTH);
    private static final HttpRequestHead POST_CHUNKED =
            request("POST", HeaderFields.of("host", "x.example.dev"), BodyFraming.CHUNKED);

    private static final HttpResponseHead OK_5 =
            new HttpResponseHead(200, HeaderFields.of("content-length", "5"), BodyFraming.LENGTH);
    private static final HttpResponseHead OK_EMPTY = new HttpResponseHead(200, HeaderFields.of(), BodyFraming.EMPTY);
    private static final HttpResponseHead OK_CHUNKED =
            new HttpResponseHead(200, HeaderFields.of(), BodyFraming.CHUNKED);
    private static final HttpResponseHead EARLY_HINTS =
            new HttpResponseHead(103, HeaderFields.of("link", "</a.css>; rel=preload"), null);

    @Test
    void aWholeExchangeWithBodiesOfKnownLength() {
        HttpExchangeRules rules = new HttpExchangeRules(POST_5);

        rules.onRequestData(2);
        rules.onRequestData(3);
        rules.onRequestEnd();
        assertThat(rules.responseStarted()).isFalse();
        rules.onResponseHead(OK_5);
        assertThat(rules.responseStarted()).isTrue();
        rules.onResponseData(5);
        rules.onResponseEnd();
        assertThat(rules.request()).isEqualTo(POST_5);
    }

    @Test
    void chunkedBodiesTakeAnyAmountAndEndAnyTime() {
        HttpExchangeRules rules = new HttpExchangeRules(POST_CHUNKED);

        rules.onRequestData(65_536);
        rules.onRequestData(1);
        rules.onRequestEnd();
        rules.onResponseHead(OK_CHUNKED);
        rules.onResponseEnd();
    }

    @Test
    void emptyBodiesEndAtOnce() {
        HttpExchangeRules rules = new HttpExchangeRules(GET);

        rules.onRequestEnd();
        rules.onResponseHead(OK_EMPTY);
        rules.onResponseEnd();
    }

    @Test
    void aLengthOfZeroIsAnEmptyBody() {
        HttpExchangeRules rules = new HttpExchangeRules(
                request("POST", HeaderFields.of("host", "x", "content-length", "0"), BodyFraming.LENGTH));

        rules.onRequestEnd();
        rules.onResponseHead(new HttpResponseHead(200, HeaderFields.of("content-length", "0"), BodyFraming.LENGTH));
        assertProtocolError(() -> rules.onResponseData(1));
    }

    @Test
    void theResponseMayStartBeforeTheRequestBodyEnds() {
        HttpExchangeRules rules = new HttpExchangeRules(POST_CHUNKED);

        rules.onRequestData(100);
        rules.onResponseHead(new HttpResponseHead(413, HeaderFields.of(), BodyFraming.EMPTY));
        rules.onResponseEnd();
        rules.onRequestData(100);
    }

    @Test
    void anyNumberOfInformationalHeadsComeBeforeTheFinalOne() {
        HttpExchangeRules rules = new HttpExchangeRules(GET);

        rules.onResponseHead(EARLY_HINTS);
        rules.onResponseHead(EARLY_HINTS);
        assertThat(rules.responseStarted()).isFalse();
        rules.onResponseHead(OK_EMPTY);
        assertThat(rules.responseStarted()).isTrue();
    }

    @Test
    void aSecondFinalHeadIsAProtocolError() {
        HttpExchangeRules rules = new HttpExchangeRules(GET);
        rules.onResponseHead(OK_EMPTY);

        assertProtocolError(() -> rules.onResponseHead(OK_EMPTY));
    }

    @Test
    void anInformationalHeadAfterTheFinalOneIsAProtocolError() {
        HttpExchangeRules rules = new HttpExchangeRules(GET);
        rules.onResponseHead(OK_CHUNKED);

        assertProtocolError(() -> rules.onResponseHead(EARLY_HINTS));
    }

    @Test
    void responseDataBeforeTheFinalHeadIsAProtocolError() {
        HttpExchangeRules rules = new HttpExchangeRules(GET);
        assertProtocolError(() -> rules.onResponseData(1));

        HttpExchangeRules afterHints = new HttpExchangeRules(GET);
        afterHints.onResponseHead(EARLY_HINTS);
        assertProtocolError(() -> afterHints.onResponseData(1));
    }

    @Test
    void theResponseEndingBeforeTheFinalHeadIsAProtocolError() {
        HttpExchangeRules rules = new HttpExchangeRules(GET);

        assertProtocolError(rules::onResponseEnd);
    }

    @Test
    void responseDataOnAnEmptyBodyIsAProtocolError() {
        HttpExchangeRules rules = new HttpExchangeRules(GET);
        rules.onResponseHead(OK_EMPTY);

        assertProtocolError(() -> rules.onResponseData(1));
    }

    @Test
    void responseDataPastTheContentLengthIsAProtocolError() {
        HttpExchangeRules rules = new HttpExchangeRules(GET);
        rules.onResponseHead(OK_5);
        rules.onResponseData(4);

        assertProtocolError(() -> rules.onResponseData(2));
    }

    @Test
    void theResponseEndingShortOfTheContentLengthIsAProtocolError() {
        HttpExchangeRules rules = new HttpExchangeRules(GET);
        rules.onResponseHead(OK_5);
        rules.onResponseData(4);

        assertProtocolError(rules::onResponseEnd);
    }

    @Test
    void responseDataAfterItsEndIsAProtocolError() {
        HttpExchangeRules rules = new HttpExchangeRules(GET);
        rules.onResponseHead(OK_CHUNKED);
        rules.onResponseEnd();

        assertProtocolError(() -> rules.onResponseData(1));
        assertProtocolError(rules::onResponseEnd);
    }

    @ParameterizedTest
    @ValueSource(strings = {"chunked", "length"})
    void aResponseToHeadIsEmpty(String framing) {
        HttpExchangeRules rules =
                new HttpExchangeRules(request("HEAD", HeaderFields.of("host", "x"), BodyFraming.EMPTY));
        HttpResponseHead head = framing.equals("chunked") ? OK_CHUNKED : OK_5;

        assertThatExceptionOfType(ProtocolException.class)
                .isThrownBy(() -> rules.onResponseHead(head))
                .withMessage("body must be empty in a response to a HEAD request")
                .satisfies(e -> assertThat(e.code()).isEqualTo(ErrorCode.VALIDATION_FAILED));
    }

    @Test
    void aResponseToHeadKeepsItsContentLength() {
        HttpExchangeRules rules =
                new HttpExchangeRules(request("HEAD", HeaderFields.of("host", "x"), BodyFraming.EMPTY));

        rules.onResponseHead(new HttpResponseHead(200, HeaderFields.of("content-length", "1043"), BodyFraming.EMPTY));
        rules.onResponseEnd();
    }

    @Test
    void headIsComparedExactly() {
        HttpExchangeRules rules =
                new HttpExchangeRules(request("head", HeaderFields.of("host", "x"), BodyFraming.EMPTY));

        rules.onResponseHead(OK_5);
    }

    @Test
    void requestDataOnAnEmptyBodyIsTheServersBug() {
        HttpExchangeRules rules = new HttpExchangeRules(GET);

        assertThatIllegalStateException().isThrownBy(() -> rules.onRequestData(1));
    }

    @Test
    void requestDataPastTheContentLengthIsTheServersBug() {
        HttpExchangeRules rules = new HttpExchangeRules(POST_5);
        rules.onRequestData(5);

        assertThatIllegalStateException().isThrownBy(() -> rules.onRequestData(1));
    }

    @Test
    void theRequestEndingShortOfTheContentLengthIsTheServersBug() {
        HttpExchangeRules rules = new HttpExchangeRules(POST_5);
        rules.onRequestData(4);

        assertThatIllegalStateException().isThrownBy(rules::onRequestEnd);
    }

    @Test
    void requestDataAfterItsEndIsTheServersBug() {
        HttpExchangeRules rules = new HttpExchangeRules(POST_CHUNKED);
        rules.onRequestEnd();

        assertThatIllegalStateException().isThrownBy(() -> rules.onRequestData(1));
        assertThatIllegalStateException().isThrownBy(rules::onRequestEnd);
    }

    @Test
    void dataIsNeverEmpty() {
        HttpExchangeRules rules = new HttpExchangeRules(POST_CHUNKED);
        rules.onResponseHead(OK_CHUNKED);

        assertThatIllegalArgumentException().isThrownBy(() -> rules.onRequestData(0));
        assertThatIllegalArgumentException().isThrownBy(() -> rules.onResponseData(0));
    }

    private static void assertProtocolError(ThrowingCallable call) {
        assertThatExceptionOfType(ProtocolException.class).isThrownBy(call).satisfies(e -> {
            assertThat(e.code()).isEqualTo(ErrorCode.PROTOCOL_ERROR);
            assertThat(e.scope()).isEqualTo(ErrorScope.SESSION);
        });
    }

    private static HttpRequestHead request(String method, HeaderFields headers, BodyFraming body) {
        return new HttpRequestHead(method, "/", headers, body);
    }
}
