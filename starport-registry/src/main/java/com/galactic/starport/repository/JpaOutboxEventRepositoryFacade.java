package com.galactic.starport.repository;

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
}
