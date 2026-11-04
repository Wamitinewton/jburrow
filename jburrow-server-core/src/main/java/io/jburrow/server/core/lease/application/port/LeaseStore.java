package io.jburrow.server.core.lease.application.port;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Where each server process writes down its claims, for the next process to read once at startup. */
public interface LeaseStore {

    /** Replaces every row of {@code instance} with {@code leases}, in one transaction. */
    void replace(UUID instance, List<LeaseRecord> leases);

    /** Every row unexpired at {@code now}, of any instance. */
    List<LeaseRecord> loadUnexpired(Instant now);

    /**
     * Deletes rows that expired before {@code cutoff}.
     *
     * @return how many rows it deleted
     */
    int purgeExpiredBefore(Instant cutoff);
}
