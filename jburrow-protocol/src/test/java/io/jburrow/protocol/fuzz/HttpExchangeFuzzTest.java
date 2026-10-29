package io.jburrow.protocol.fuzz;

import static org.assertj.core.api.Assertions.assertThat;

import com.code_intelligence.jazzer.api.FuzzedDataProvider;
import com.code_intelligence.jazzer.junit.FuzzTest;
import io.jburrow.protocol.control.BodyFraming;
import io.jburrow.protocol.control.HeaderFields;
import io.jburrow.protocol.control.HttpRequestHead;
import io.jburrow.protocol.control.HttpResponseHead;
import io.jburrow.protocol.stream.HttpExchangeRules;
import io.jburrow.protocol.stream.StreamEvent;
import io.jburrow.protocol.stream.StreamLifecycle;
import io.jburrow.protocol.stream.StreamOutcome;
import io.jburrow.protocol.wire.ErrorCode;
import io.jburrow.protocol.wire.ErrorScope;
import io.jburrow.protocol.wire.ProtocolException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Drives one HTTP stream, seen from the server, through an arbitrary sequence of frames: the server's request body and
 * its end, which it sends only as the rules allow, and anything at all from the client, in any order.
 *
 * <p>Each frame goes through the stream's {@link StreamLifecycle} first and reaches {@link HttpExchangeRules} only if the
 * lifecycle accepts it, as a session applies them. The property: a frame either passes or ends the session with a
 * {@link ProtocolException} whose code a head or a stream rule may raise, never another exception; and what passes
 * agrees with a model of the response, so no response body is longer than its {@code content-length}, none ends
 * short of it, and none starts before exactly one final head.
 */
class HttpExchangeFuzzTest {

    private static final Set<ErrorCode> STREAM_RULE_ERRORS =
            Set.of(ErrorCode.PROTOCOL_ERROR, ErrorCode.VALIDATION_FAILED);
    private static final String[] METHODS = {"GET", "HEAD", "POST", "PUT", "OPTIONS", "DELETE"};
    private static final int[] STATUSES = {100, 101, 103, 199, 200, 201, 204, 206, 304, 404, 500, 599};
    private static final BodyFraming[] FRAMINGS = BodyFraming.values();
    private static final int MAX_LENGTH = 1 << 20;

    private enum Step {
        REQUEST_DATA,
        REQUEST_END,
        RESPONSE_HEAD,
        RESPONSE_DATA,
        RESPONSE_END,
        CLIENT_RESET
    }

    private static final Step[] STEPS = Step.values();

    @FuzzTest(maxDuration = "2m")
    void exchanges(FuzzedDataProvider data) {
        HttpRequestHead request = request(data);
        StreamLifecycle stream = new StreamLifecycle();
        HttpExchangeRules rules = new HttpExchangeRules(request);
        Model model = new Model(request);

        while (data.remainingBytes() > 0 && !stream.isClosed()) {
            Step step = data.pickValue(STEPS);
            switch (step) {
                case REQUEST_DATA -> {
                    int bytes = data.consumeInt(1, 65_536);
                    if (model.requestMayCarry(bytes) && accepted(stream, StreamEvent.DATA_SENT)) {
                        rules.onRequestData(bytes);
                        model.requestSent(bytes);
                    }
                }
                case REQUEST_END -> {
                    if (model.requestMayEnd() && accepted(stream, StreamEvent.CLOSE_SENT)) {
                        rules.onRequestEnd();
                        model.requestEnded();
                    }
                }
                case RESPONSE_HEAD -> {
                    HttpResponseHead head;
                    try {
                        head = response(data);
                    } catch (ProtocolException rejected) {
                        assertDefined(rejected);
                        return;
                    }
                    if (!peerFrame(
                            stream,
                            StreamEvent.HEADERS_RECEIVED,
                            () -> rules.onResponseHead(head),
                            () -> model.responseHead(head))) {
                        return;
                    }
                }
                case RESPONSE_DATA -> {
                    int bytes = data.consumeInt(1, 65_536);
                    if (!peerFrame(
                            stream,
                            StreamEvent.DATA_RECEIVED,
                            () -> rules.onResponseData(bytes),
                            () -> model.responseData(bytes))) {
                        return;
                    }
                }
                case RESPONSE_END -> {
                    if (!peerFrame(stream, StreamEvent.CLOSE_RECEIVED, rules::onResponseEnd, model::responseEnded)) {
                        return;
                    }
                }
                case CLIENT_RESET -> stream.on(StreamEvent.RST_RECEIVED);
            }
        }
    }

    /**
     * Applies a frame from the client: to the lifecycle, then, if the lifecycle delivers it, to the rules, and if they
     * pass it, to the model. Returns {@code false} once the session has ended, which it does exactly when the rules
     * refused the frame.
     */
    private static boolean peerFrame(StreamLifecycle stream, StreamEvent event, Runnable rule, Runnable passed) {
        if (!(stream.on(event) instanceof StreamOutcome.Accept)) {
            return true;
        }
        try {
            rule.run();
        } catch (ProtocolException rejected) {
            assertDefined(rejected);
            return false;
        }
        passed.run();
        return true;
    }

    private static boolean accepted(StreamLifecycle stream, StreamEvent event) {
        boolean allowed =
                switch (stream.state()) {
                    case OPEN -> true;
                    case HALF_CLOSED_LOCAL -> false;
                    case HALF_CLOSED_REMOTE -> true;
                    case CLOSED -> false;
                };
        return allowed && stream.on(event) instanceof StreamOutcome.Accept;
    }

    private static void assertDefined(ProtocolException rejected) {
        assertThat(rejected.scope()).isEqualTo(ErrorScope.SESSION);
        assertThat(rejected.code()).isIn(STREAM_RULE_ERRORS);
    }

    private static HttpRequestHead request(FuzzedDataProvider data) {
        String method = data.pickValue(METHODS);
        BodyFraming body = data.pickValue(FRAMINGS);
        List<String> headers = new ArrayList<>(List.of("host", "x.example.dev"));
        if (body == BodyFraming.LENGTH) {
            headers.addAll(List.of("content-length", Integer.toString(data.consumeInt(0, MAX_LENGTH))));
        }
        return new HttpRequestHead(method, "/", HeaderFields.of(headers.toArray(String[]::new)), body);
    }

    /** A response head from arbitrary choices, which the record may refuse as the codec would. */
    private static HttpResponseHead response(FuzzedDataProvider data) {
        int status = data.pickValue(STATUSES);
        BodyFraming body = data.consumeBoolean() ? data.pickValue(FRAMINGS) : null;
        HeaderFields headers = data.consumeBoolean()
                ? HeaderFields.of("content-length", Integer.toString(data.consumeInt(0, MAX_LENGTH)))
                : HeaderFields.of();
        return new HttpResponseHead(status, headers, body);
    }

    /** What a correct exchange has done so far, kept apart from the rules it checks. */
    private static final class Model {

        private final boolean headRequest;
        private final BodyFraming requestBody;
        private long requestLeft;
        private boolean requestEnded;
        private HttpResponseHead finalHead;
        private long responseLeft;
        private boolean responseEnded;

        Model(HttpRequestHead request) {
            headRequest = request.method().equals("HEAD");
            requestBody = request.body();
            requestLeft = request.headers().contentLength().orElse(0);
        }

        boolean requestMayCarry(int bytes) {
            return !requestEnded
                    && switch (requestBody) {
                        case EMPTY -> false;
                        case LENGTH -> bytes <= requestLeft;
                        case CHUNKED -> true;
                    };
        }

        void requestSent(int bytes) {
            if (requestBody == BodyFraming.LENGTH) {
                requestLeft -= bytes;
            }
        }

        boolean requestMayEnd() {
            return !requestEnded && requestLeft == 0;
        }

        void requestEnded() {
            requestEnded = true;
        }

        void responseHead(HttpResponseHead head) {
            assertThat(finalHead).as("a head after the final one passed").isNull();
            if (head.informational()) {
                return;
            }
            if (headRequest) {
                assertThat(head.body())
                        .as("the final head of a response to HEAD")
                        .isEqualTo(BodyFraming.EMPTY);
            }
            finalHead = head;
            responseLeft = head.body() == BodyFraming.LENGTH
                    ? head.headers().contentLength().orElseThrow()
                    : 0;
        }

        void responseData(int bytes) {
            assertThat(finalHead)
                    .as("response DATA before the final head passed")
                    .isNotNull();
            assertThat(responseEnded).as("response DATA after its end passed").isFalse();
            assertThat(finalHead.body()).as("response DATA on an empty body").isNotEqualTo(BodyFraming.EMPTY);
            if (finalHead.body() == BodyFraming.LENGTH) {
                assertThat((long) bytes)
                        .as("response DATA past its content-length")
                        .isLessThanOrEqualTo(responseLeft);
                responseLeft -= bytes;
            }
        }

        void responseEnded() {
            assertThat(finalHead).as("the response ended before its final head").isNotNull();
            assertThat(responseLeft)
                    .as("the response ended short of its content-length")
                    .isZero();
            responseEnded = true;
        }
    }
}
