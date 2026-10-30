package io.jburrow.server.e2e;

import static java.nio.charset.StandardCharsets.UTF_8;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.OutputStreamAppender;
import java.io.ByteArrayOutputStream;
import java.util.List;
import org.slf4j.LoggerFactory;
import org.springframework.boot.logging.logback.StructuredLogEncoder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Every log line of the JVM while open, encoded as ECS JSON by the encoder the {@code prod} profile's console uses.
 * Logback is set up once per JVM, by whichever test context starts first, so a test that needs the production format
 * attaches its own appender rather than relying on the profile.
 */
final class EcsLogCapture implements AutoCloseable {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    private final OutputStreamAppender<ILoggingEvent> appender = new OutputStreamAppender<>();
    private final Logger root;

    private EcsLogCapture() {
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        StructuredLogEncoder encoder = new StructuredLogEncoder();
        encoder.setContext(context);
        encoder.setFormat("ecs");
        encoder.start();
        appender.setContext(context);
        appender.setName("ecs-capture");
        appender.setEncoder(encoder);
        appender.setOutputStream(bytes);
        appender.start();
        root = context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        root.addAppender(appender);
    }

    static EcsLogCapture start() {
        return new EcsLogCapture();
    }

    /** Every line captured so far, as written. */
    String text() {
        return bytes.toString(UTF_8);
    }

    /** Every line captured so far, parsed; a line that isn't JSON fails the test. */
    List<JsonNode> lines() {
        return text().lines().map(JSON::readTree).toList();
    }

    /** The {@code message} of every line captured so far. */
    List<String> messages() {
        return lines().stream().map(line -> line.path("message").asString()).toList();
    }

    @Override
    public void close() {
        root.detachAppender(appender);
        appender.stop();
    }
}
