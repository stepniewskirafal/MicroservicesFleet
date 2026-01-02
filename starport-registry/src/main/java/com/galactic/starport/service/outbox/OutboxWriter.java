package com.galactic.starport.service.outbox;

import com.galactic.starport.repository.OutboxEventRepositoryFacade;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
class OutboxWriter {
    private final OutboxEventRepositoryFacade outboxEventRepositoryFacade;

    @Transactional(propagation = Propagation.MANDATORY)
    public void save(
            String binding,
            String eventType,
            String messageKey,
            Map<String, Object> payload,
            Map<String, Object> headers) {
        outboxEventRepositoryFacade.saveEvent(binding, eventType, messageKey, payload, headers);
    }
}
