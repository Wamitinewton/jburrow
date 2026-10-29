package io.jburrow.server.e2e;

import io.jburrow.server.core.tunnel.adapter.netty.control.SessionContext;
import io.jburrow.server.core.tunnel.adapter.netty.control.SessionRegistry;
import io.jburrow.server.core.tunnel.adapter.netty.control.SessionStreams;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.TestExecutionExceptionHandler;
import org.springframework.test.context.junit.jupiter.SpringExtension;

/**
 * When a test fails, prints the flow state of every live session on the server, then every platform thread's stack,
 * and rethrows. A stall in flow control shows up as a timeout, and this says which window was empty, who was waiting
 * for it and where each thread was.
 *
 * <p>Each session is described on its own event loop, which owns its state. A loop that doesn't answer in
 * {@link #LOOP_TIMEOUT} is reported as stuck, and its thread's stack is in the dump that follows.
 */
final class FlowStateDump implements TestExecutionExceptionHandler {

    private static final Duration LOOP_TIMEOUT = Duration.ofSeconds(2);

    @Override
    public void handleTestExecutionException(ExtensionContext context, Throwable failure) throws Throwable {
        StringBuilder dump = new StringBuilder("\n=== Flow state after the failure of ")
                .append(context.getDisplayName())
                .append(" ===\n");
        try {
            SessionRegistry sessions =
                    SpringExtension.getApplicationContext(context).getBean(SessionRegistry.class);
            for (SessionContext session : sessions.all()) {
                dump.append(describe(session)).append('\n');
            }
        } catch (RuntimeException e) {
            dump.append("(could not read the sessions: ").append(e).append(")\n");
        }
        dump.append("=== Threads ===\n");
        for (Map.Entry<Thread, StackTraceElement[]> thread :
                Thread.getAllStackTraces().entrySet()) {
            dump.append('"')
                    .append(thread.getKey().getName())
                    .append("\" ")
                    .append(thread.getKey().getState())
                    .append('\n');
            for (StackTraceElement frame : thread.getValue()) {
                dump.append("    at ").append(frame).append('\n');
            }
        }
        System.out.println(dump);
        throw failure;
    }

    private static String describe(SessionContext session) {
        String id = "session " + session.session().id();
        try {
            return session.channel()
                    .eventLoop()
                    .submit(() -> id + " " + session.session().state() + ": "
                            + session.streams()
                                    .map(SessionStreams::describeFlow)
                                    .orElse("no streams"))
                    .get(LOOP_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            return id + ": its event loop didn't answer within " + LOOP_TIMEOUT;
        } catch (ExecutionException e) {
            return id + ": " + e.getCause();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return id + ": interrupted";
        }
    }
}
