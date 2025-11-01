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
public class OutboxWriter {
    private final OutboxEventJpaRepository repo;

    /**
     * MANDATORY pilnuje, by zdarzenie powstało w tej samej transakcji co zmiana stanu domeny.
     */
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
