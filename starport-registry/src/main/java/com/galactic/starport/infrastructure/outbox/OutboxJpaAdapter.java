package com.galactic.starport.infrastructure.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.galactic.starport.domain.port.OutboxPort;
import java.time.Instant;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
public class OutboxJpaAdapter implements OutboxPort {

    private final OutboxEventJpaRepository repo;
    private final ObjectMapper mapper;

    @Override
    @Transactional
    public void save(
            String eventType, String binding, String messageKey, String payloadJson, Map<String, Object> headers) {
        try {
            var e = new OutboxEventEntity();
            e.setEventType(eventType);
            e.setBinding(binding);
            e.setMessageKey(messageKey);
            e.setPayloadJson(payloadJson);
            e.setHeadersJson(headers == null ? null : mapper.writeValueAsString(headers));
            e.setSentAt(Instant.now());
            repo.save(e);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to persist outbox event", ex);
        }
    }
}
