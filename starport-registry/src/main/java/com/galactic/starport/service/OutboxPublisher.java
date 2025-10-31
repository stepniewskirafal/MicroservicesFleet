package com.galactic.starport.service;

import com.galactic.starport.repository.OutboxEventEntity;
import com.galactic.starport.repository.OutboxEventJpaRepository;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.integration.support.MessageBuilder;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.Message;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
@Slf4j
public class OutboxPublisher {
    private final OutboxEventJpaRepository repo;
    private final StreamBridge streamBridge;

    @Value("${outbox.batch-size:50}")
    int batchSize;

    @Value("${outbox.max-attempts:10}")
    int maxAttempts;

    @Scheduled(fixedDelayString = "${outbox.poll-interval-ms:10000}")
    @Transactional
    public void pollAndPublish() {
        List<OutboxEventEntity> batch = repo.lockBatchPending(batchSize);
        if (batch.isEmpty()) return;

        for (OutboxEventEntity e : batch) {
            try {
                Map<String, Object> payload = e.getPayloadJson();
                Map<String, Object> headers = e.getHeadersJson();

                Message<?> msg = MessageBuilder.withPayload(payload)
                        // Header jest ignorowany, jeśli binder to nie Kafka – zostawiamy dla idempotencji
                        .setHeader(KafkaHeaders.KEY, e.getMessageKey())
                        .copyHeaders(headers == null ? Map.of() : headers)
                        .build();

                boolean ok = streamBridge.send(e.getBinding(), msg);
                if (ok) {
                    e.markSent();
                } else {
                    handleFailure(e, null);
                }
            } catch (Exception ex) {
                handleFailure(e, ex);
            }
        }
        // brak saveAll – @Transactional + dirty checking załatwi update statusów/attempts
    }

    private void handleFailure(OutboxEventEntity e, Exception ex) {
        e.bumpAttempts();
        if (e.getAttempts() >= maxAttempts) {
            e.markFailed();
            log.warn("Outbox permanently failed id={} attempts={}", e.getId(), e.getAttempts(), ex);
        } else {
            log.info("Outbox temporary failure id={} attempts={}", e.getId(), e.getAttempts(), ex);
        }
    }
}
