package io.jburrow.server.core.portpool.domain;

import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;

/** A clock that moves only when a test moves it. */
public final class MutableClock implements InstantSource {

    private Instant now = Instant.parse("2026-01-01T00:00:00Z");

    @Override
    public Instant instant() {
        return now;
    }

    public void advance(Duration duration) {
        now = now.plus(duration);
    }
}
