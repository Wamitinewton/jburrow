package io.jburrow.protocol.control;

import static io.jburrow.protocol.control.ValidationAssertions.assertInvalid;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class HttpRequestHeadTest {

    private static final HeaderFields HOST_ONLY = HeaderFields.of("host", "quiet-otter-4f2a.example.dev");
    private static final String TARGET_RULE =
            "target must be 1 to 4096 visible ASCII characters starting with /, or * for OPTIONS";

    @Test
    void acceptsTheArchitectureExample() {
        HttpRequestHead head = new HttpRequestHead(
                "POST",
                "/api/items?page=2",
                HeaderFields.of(
                        "host", "quiet-otter-4f2a.example.dev",
                        "content-type", "application/json",
                        "content-length", "27",
                        "x-forwarded-for", "203.0.113.9",
                        "x-forwarded-proto", "https",
                        "x-forwarded-host", "quiet-otter-4f2a.example.dev",
                        "via", "1.1 jburrow"),
                BodyFraming.LENGTH);

        assertThat(head.headers().contentLength()).hasValue(27);
    }

    @ParameterizedTest
    @ValueSource(strings = {"GET", "POST", "PROPFIND", "get", "M-SEARCH", "X!#$%&'*+.^_`|~1"})
    void methodsAreTokensWithTheirCaseKept(String method) {
        assertThat(get(method, "/").method()).isEqualTo(method);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "GE T", "GET/", "GÉT", "ABCDEFGHIJKLMNOPQRSTUVWXYZABCDEFG"})
    void methodsThatAreNotShortTokensAreRefused(String method) {
        assertInvalid(() -> get(method, "/"), "method must match [A-Za-z0-9!#$%&'*+.^_`|~-]{1,32}");
    }

    @Test
    void connectIsRefused() {
        assertInvalid(() -> get("CONNECT", "/"), "method must not be CONNECT");
    }

    @ParameterizedTest
    @ValueSource(strings = {"/", "/api/items?page=2", "/a/../b", "/%2e%2e/", "/?q=\"x\"&y=~"})
    void targetsInOriginFormAreSentAsReceived(String target) {
        assertThat(get("GET", target).target()).isEqualTo(target);
    }

    @Test
    void asteriskIsOnlyForOptions() {
        assertThat(get("OPTIONS", "*").target()).isEqualTo("*");
        assertInvalid(() -> get("GET", "*"), TARGET_RULE);
        assertInvalid(() -> get("options", "*"), TARGET_RULE);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "http://example.dev/", "example.dev:443", "a", "/a b", "/a\tb", "/café", "/\u007f"})
    void targetsInAnyOtherFormAreRefused(String target) {
        assertInvalid(() -> get("GET", target), TARGET_RULE);
    }

    @Test
    void targetsAreAtMost4096Characters() {
        get("GET", "/" + "a".repeat(4_095));
        assertInvalid(() -> get("GET", "/" + "a".repeat(4_096)), TARGET_RULE);
    }

    @Test
    void exactlyOneHost() {
        String rule = "headers must have exactly one host field";
        assertInvalid(() -> new HttpRequestHead("GET", "/", HeaderFields.of("accept", "*"), BodyFraming.EMPTY), rule);
        assertInvalid(
                () -> new HttpRequestHead("GET", "/", HeaderFields.of("host", "a", "host", "a"), BodyFraming.EMPTY),
                rule);
    }

    @Test
    void emptyAllowsNoContentLengthOrZero() {
        new HttpRequestHead("GET", "/", HOST_ONLY, BodyFraming.EMPTY);
        new HttpRequestHead("POST", "/", HeaderFields.of("host", "a", "content-length", "0"), BodyFraming.EMPTY);
        assertInvalid(
                () -> new HttpRequestHead(
                        "POST", "/", HeaderFields.of("host", "a", "content-length", "1"), BodyFraming.EMPTY),
                "body must not be empty with a content-length other than 0");
    }

    @Test
    void lengthNeedsOneContentLength() {
        new HttpRequestHead("POST", "/", HeaderFields.of("host", "a", "content-length", "0"), BodyFraming.LENGTH);
        assertInvalid(
                () -> new HttpRequestHead("POST", "/", HOST_ONLY, BodyFraming.LENGTH),
                "body must not be length without a content-length field");
        assertInvalid(
                () -> new HttpRequestHead(
                        "POST",
                        "/",
                        HeaderFields.of("host", "a", "content-length", "1", "content-length", "1"),
                        BodyFraming.LENGTH),
                "headers must not repeat content-length");
        assertInvalid(
                () -> new HttpRequestHead(
                        "POST", "/", HeaderFields.of("host", "a", "content-length", "-1"), BodyFraming.LENGTH),
                "headers content-length must be 1 to 16 decimal digits");
    }

    @Test
    void chunkedHasNoContentLength() {
        new HttpRequestHead("POST", "/", HOST_ONLY, BodyFraming.CHUNKED);
        assertInvalid(
                () -> new HttpRequestHead(
                        "POST", "/", HeaderFields.of("host", "a", "content-length", "5"), BodyFraming.CHUNKED),
                "body must not be chunked with a content-length field");
    }

    @Test
    void everyFieldIsRequired() {
        assertInvalid(() -> new HttpRequestHead(null, "/", HOST_ONLY, BodyFraming.EMPTY), "method is required");
        assertInvalid(() -> new HttpRequestHead("GET", null, HOST_ONLY, BodyFraming.EMPTY), "target is required");
        assertInvalid(() -> new HttpRequestHead("GET", "/", null, BodyFraming.EMPTY), "headers is required");
        assertInvalid(() -> new HttpRequestHead("GET", "/", HOST_ONLY, null), "body is required");
    }

    private static HttpRequestHead get(String method, String target) {
        return new HttpRequestHead(method, target, HOST_ONLY, BodyFraming.EMPTY);
    }
}
