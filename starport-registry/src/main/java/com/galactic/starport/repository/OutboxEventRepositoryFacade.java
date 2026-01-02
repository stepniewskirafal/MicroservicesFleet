package com.galactic.starport.repository;

import java.util.Map;

public interface OutboxEventRepositoryFacade {
    void saveEvent(
            String binding,
            String eventType,
            String messageKey,
            Map<String, Object> payload,
            Map<String, Object> headers);
}
