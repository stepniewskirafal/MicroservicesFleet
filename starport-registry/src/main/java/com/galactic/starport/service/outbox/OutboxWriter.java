package com.galactic.starport.service.outbox;

import com.galactic.starport.repository.OutboxEventRepositoryFacade;
import java.util.Map;
import java.util.Objects;
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
        Objects.requireNonNull(binding, "binding must not be null");
        Objects.requireNonNull(eventType, "eventType must not be null");
        Objects.requireNonNull(messageKey, "messageKey must not be null");
        Objects.requireNonNull(payload, "payload must not be null");
        outboxEventRepositoryFacade.saveEvent(binding, eventType, messageKey, payload, headers);
    }
}
