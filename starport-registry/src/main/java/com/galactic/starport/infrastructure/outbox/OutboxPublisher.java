package com.galactic.starport.infrastructure.outbox;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.data.domain.PageRequest;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
public class OutboxPublisher {

    private final OutboxEventJpaRepository repo;
    private final StreamBridge streamBridge;
    private final ObjectMapper mapper;

    @Scheduled(fixedDelayString = "${outbox.poll-interval-ms:1000}")
    @Transactional
    public void publishBatch() {
        var batch = repo.fetchBatchPending(PageRequest.of(0, 100));
        for (var e : batch) {
            try {
                Map<String, Object> headers = e.getHeadersJson() == null
                        ? Map.of()
                        : mapper.readValue(e.getHeadersJson(), new TypeReference<>() {});
                Message<String> msg = MessageBuilder.withPayload(e.getPayloadJson())
                        .copyHeaders(headers)
                        .build();

                boolean ok = streamBridge.send(e.getBinding(), msg); // wysyłka na BINDING
                if (ok) e.markSent();
                else e.bumpAttempts();
            } catch (Exception ex) {
                e.bumpAttempts();
                if (e.getAttempts() >= 10) e.markFailed();
            }
        }
        repo.saveAll(batch);
    }
}
