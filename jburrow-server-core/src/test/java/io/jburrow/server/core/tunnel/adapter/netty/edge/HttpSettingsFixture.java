package io.jburrow.server.core.tunnel.adapter.netty.edge;

import java.time.Duration;

/** The {@code jburrow.http.*} defaults, for tests that build {@link EdgeSettings} by hand. */
public final class HttpSettingsFixture {

    private HttpSettingsFixture() {}

    public static HttpSettings defaults() {
        return new HttpSettings(
                "localhost",
                100L * 1024 * 1024,
                Duration.ofSeconds(60),
                Duration.ofSeconds(75),
                16 * 1024,
                Duration.ofSeconds(1),
                16L * 1024 * 1024,
                2L * 1024 * 1024);
    }
}
