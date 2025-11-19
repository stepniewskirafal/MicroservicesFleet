package com.galactic.starport.service;

import com.galactic.starport.repository.OutboxEventEntity;
import com.galactic.starport.repository.OutboxEventJpaRepository;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
class OutboxWriter {
    private final OutboxEventJpaRepository repo;

    @Transactional(propagation = Propagation.MANDATORY)
    public void append(
            String binding,
            String eventType,
            String messageKey,
            Map<String, Object> payload,
            Map<String, Object> headers) {
        OutboxEventEntity e = new OutboxEventEntity();
        e.setBinding(binding);
        e.setEventType(eventType);
        e.setMessageKey(messageKey);
        e.setPayloadJson(payload);
        e.setHeadersJson(headers);
        e.setStatus(OutboxEventEntity.OutboxStatus.PENDING);
        repo.save(e);
    }
}
