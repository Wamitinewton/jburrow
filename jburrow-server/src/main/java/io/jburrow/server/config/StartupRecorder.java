package io.jburrow.server.config;

import io.jburrow.server.api.instance.domain.ServerInstance;
import java.util.Objects;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;

/**
 * Records this start once the application is ready. A failure is logged and swallowed: the API stays up for diagnosis
 * and {@code /meta} reports {@code INSTANCE_NOT_RECORDED}.
 */
final class StartupRecorder {

    private static final Logger log = LoggerFactory.getLogger(StartupRecorder.class);

    private final Supplier<ServerInstance> recordStartup;

    StartupRecorder(Supplier<ServerInstance> recordStartup) {
        this.recordStartup = Objects.requireNonNull(recordStartup, "recordStartup");
    }

    @EventListener(ApplicationReadyEvent.class)
    void onReady() {
        try {
            ServerInstance instance = recordStartup.get();
            log.info(
                    "server_instance recorded: id={} version={} commit={}",
                    instance.id(),
                    instance.build().version(),
                    instance.build().commit());
        } catch (RuntimeException ex) {
            log.error("server_instance record failed", ex);
        }
    }
}
