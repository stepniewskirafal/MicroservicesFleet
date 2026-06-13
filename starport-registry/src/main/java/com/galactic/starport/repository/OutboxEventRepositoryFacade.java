package com.galactic.starport.repository;

import java.util.List;
import java.util.Map;

public interface OutboxEventRepositoryFacade {
    void saveEvent(
            String binding,
            String eventType,
            String messageKey,
            Map<String, Object> payload,
            Map<String, Object> headers);

    /**
     * Lock and return a batch of PENDING events for relay (FOR UPDATE SKIP LOCKED). Must run inside
     * the caller's transaction so the row locks are held until publish + state transition commit.
     */
    List<PendingOutboxEvent> lockPendingBatch(int batchSize);

    /** Mark a relayed event as successfully published. */
    void markSent(long id);

    /** Record a delivery failure: bump the attempt count and dead-letter once it hits {@code maxAttempts}. */
    OutboxFailureOutcome recordFailure(long id, int maxAttempts);

    /** Count events still PENDING — saturation signal for the relay pipeline. */
    long countPending();
}
