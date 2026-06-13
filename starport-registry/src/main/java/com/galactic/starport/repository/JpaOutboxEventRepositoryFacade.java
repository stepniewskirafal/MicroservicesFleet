package com.galactic.starport.repository;

import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
class JpaOutboxEventRepositoryFacade implements OutboxEventRepositoryFacade {
    private final OutboxEventJpaRepository outboxEventJpaRepository;

    @Override
    public void saveEvent(
            String binding,
            String eventType,
            String messageKey,
            Map<String, Object> payload,
            Map<String, Object> headers) {
        OutboxEventEntity eventEntity = new OutboxEventEntity();
        eventEntity.setBinding(binding);
        eventEntity.setEventType(eventType);
        eventEntity.setMessageKey(messageKey);
        eventEntity.setPayloadJson(payload);
        eventEntity.setHeadersJson(headers);
        eventEntity.setStatus(OutboxEventEntity.OutboxStatus.PENDING);
        outboxEventJpaRepository.save(eventEntity);
    }

    @Override
    public List<PendingOutboxEvent> lockPendingBatch(int batchSize) {
        return outboxEventJpaRepository.lockBatchPending(batchSize).stream()
                .map(JpaOutboxEventRepositoryFacade::toPending)
                .toList();
    }

    @Override
    public void markSent(long id) {
        // findById is a first-level-cache hit: the entity was loaded (and row-locked) by
        // lockPendingBatch in this same transaction, so its dirty-checked state flushes on commit.
        findManaged(id).markSent();
    }

    @Override
    public OutboxFailureOutcome recordFailure(long id, int maxAttempts) {
        OutboxEventEntity event = findManaged(id);
        event.bumpAttempts();
        boolean deadLettered = event.getAttempts() >= maxAttempts;
        if (deadLettered) {
            event.markFailed();
        }
        return new OutboxFailureOutcome(event.getAttempts(), deadLettered);
    }

    @Override
    public long countPending() {
        return outboxEventJpaRepository.countPending();
    }

    private OutboxEventEntity findManaged(long id) {
        return outboxEventJpaRepository
                .findById(id)
                .orElseThrow(() -> new IllegalStateException("Outbox event vanished mid-relay: id=" + id));
    }

    private static PendingOutboxEvent toPending(OutboxEventEntity e) {
        return new PendingOutboxEvent(
                e.getId(), e.getBinding(), e.getEventType(), e.getMessageKey(), e.getPayloadJson(), e.getHeadersJson());
    }
}
