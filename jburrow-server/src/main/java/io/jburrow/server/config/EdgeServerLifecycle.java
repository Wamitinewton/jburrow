package io.jburrow.server.config;

import io.jburrow.server.core.tunnel.adapter.netty.edge.EdgeServer;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.SmartLifecycle;

/**
 * Ties the {@link EdgeServer} to the application's lifecycle: it starts after the web server, so tunnels are only
 * accepted once everything else is up, and stops before the web server's graceful shutdown begins.
 */
final class EdgeServerLifecycle implements SmartLifecycle {

    /**
     * Above the web server's start-stop phase ({@link WebServerApplicationContext#START_STOP_LIFECYCLE_PHASE}) and its
     * graceful shutdown phase ({@link WebServerApplicationContext#GRACEFUL_SHUTDOWN_PHASE}): later phases start later
     * and stop earlier.
     */
    static final int PHASE = SmartLifecycle.DEFAULT_PHASE - 512;

    /** Bounds the whole stop, inside the 20-second shutdown phase timeout. */
    static final Duration STOP_TIMEOUT = Duration.ofSeconds(15);

    private static final Logger log = LoggerFactory.getLogger(EdgeServerLifecycle.class);

    private final EdgeServer edge;
    private volatile boolean running;

    EdgeServerLifecycle(EdgeServer edge) {
        this.edge = Objects.requireNonNull(edge, "edge");
    }

    @Override
    public void start() {
        edge.start();
        running = true;
    }

    @Override
    public void stop(Runnable callback) {
        running = false;
        edge.beginShutdown();
        edge.stop(STOP_TIMEOUT).whenComplete((done, failure) -> {
            if (failure != null) {
                log.warn("The edge did not stop cleanly within {}", STOP_TIMEOUT, failure);
            }
            callback.run();
        });
    }

    @Override
    public void stop() {
        CountDownLatch stopped = new CountDownLatch(1);
        stop(stopped::countDown);
        try {
            stopped.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        return PHASE;
    }

    @Override
    public boolean isAutoStartup() {
        return true;
    }
}
