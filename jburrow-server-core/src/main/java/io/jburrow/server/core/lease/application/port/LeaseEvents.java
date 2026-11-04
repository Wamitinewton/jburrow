package io.jburrow.server.core.lease.application.port;

import java.time.Duration;

/** What the lease keeper reports about its writes, for logs and metrics. */
public interface LeaseEvents {

    /** A snapshot of {@code leases} rows was written. */
    void written(int leases, Duration took);

    /**
     * A write failed; the next tick tries again with a fresh snapshot.
     *
     * @param consecutive how many writes in a row have failed, this one included
     */
    void writeFailed(RuntimeException failure, Duration took, int consecutive);
}
