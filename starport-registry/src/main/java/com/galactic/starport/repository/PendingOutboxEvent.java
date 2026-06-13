package com.galactic.starport.repository;

import java.util.Map;

/**
 * Read model for the outbox relay: exactly the fields {@code InboxPublisher} needs to publish an
 * event, without handing the JPA entity to the service layer. State transitions (sent / failed)
 * flow back through {@link OutboxEventRepositoryFacade} by id, so the entity never leaves this
 * package.
 */
public record PendingOutboxEvent(
        long id,
        String binding,
        String eventType,
        String messageKey,
        Map<String, Object> payloadJson,
        Map<String, Object> headersJson) {}
