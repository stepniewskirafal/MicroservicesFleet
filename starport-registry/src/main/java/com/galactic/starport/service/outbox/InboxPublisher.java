package com.galactic.starport.service.outbox;

import com.galactic.starport.repository.OutboxEventEntity;
import com.galactic.starport.repository.OutboxEventJpaRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.transport.Kind;
import io.micrometer.observation.transport.ReceiverContext;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
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
@Slf4j
class InboxPublisher {
    private static final String OBS_PUBLISH = "reservations.inbox.publish";
    private static final String METRIC_POLL_DURATION = "reservations.inbox.poll.duration";
    private static final String METRIC_BATCH_SIZE = "reservations.inbox.poll.batch.size";
    private static final String METRIC_DEAD_LETTER = "reservations.outbox.dead.letter";
    private final OutboxEventJpaRepository repo;
    private final StreamBridge streamBridge;
    private final ObservationRegistry observationRegistry;
    private final MeterRegistry meterRegistry;
    private final int batchSize;
    private final int maxAttempts;

    InboxPublisher(
            OutboxEventJpaRepository repo,
            StreamBridge streamBridge,
            ObservationRegistry observationRegistry,
            MeterRegistry meterRegistry,
            @Value("${outbox.batch-size:50}") int batchSize,
            @Value("${outbox.max-attempts:10}") int maxAttempts) {
        this.repo = repo;
        this.streamBridge = streamBridge;
        this.observationRegistry = observationRegistry;
        this.meterRegistry = meterRegistry;
        this.batchSize = batchSize;
        this.maxAttempts = maxAttempts;
    }

    @Scheduled(fixedDelayString = "${outbox.poll-interval-ms:10000}")
    @Transactional
    public void pollAndPublish() {
        Timer.Sample sample = Timer.start(meterRegistry);
        String outcome = "success";
        boolean anyFailure = false;
        int actualBatchSize = 0;
        try {
            List<OutboxEventEntity> batch = repo.lockBatchPending(batchSize);
            actualBatchSize = batch.size();
            if (batch.isEmpty()) {
                outcome = "empty";
                return;
            }
            DistributionSummary.builder(METRIC_BATCH_SIZE)
                    .description("Outbox batch size fetched during poll")
                    .baseUnit("events")
                    .register(meterRegistry)
                    .record(batch.size());
            for (OutboxEventEntity event : batch) {
                if (!processSingleEvent(event)) {
                    anyFailure = true;
                }
            }
            if (anyFailure) {
                outcome = "error";
            }
        } catch (Exception ex) {
            outcome = "error";
            throw ex;
        } finally {
            sample.stop(Timer.builder(METRIC_POLL_DURATION)
                    .description("Outbox poll+publish batch duration")
                    .tag("outcome", outcome)
                    .tag("batchSize", String.valueOf(actualBatchSize))
                    .register(meterRegistry));
        }
    }

    private boolean processSingleEvent(OutboxEventEntity outboxEvent) {
        ReceiverContext<Map<String, String>> receiverCtx = new ReceiverContext<>(Map::get, Kind.CONSUMER);
        receiverCtx.setCarrier(toStringHeaders(outboxEvent.getHeadersJson()));
        receiverCtx.setRemoteServiceName("kafka");
        Observation publishObs = Observation.createNotStarted(OBS_PUBLISH, () -> receiverCtx, observationRegistry)
                .lowCardinalityKeyValue("binding", outboxEvent.getBinding())
                .lowCardinalityKeyValue("eventType", outboxEvent.getEventType())
                .highCardinalityKeyValue("outboxId", String.valueOf(outboxEvent.getId()));
        publishObs.start();
        try (Observation.Scope scope = publishObs.openScope()) {
            Message<?> msg = buildMessage(outboxEvent);
            boolean sent = streamBridge.send(outboxEvent.getBinding(), msg);
            if (!sent) {
                throw new IllegalStateException("StreamBridge.send returned false for outboxId=" + outboxEvent.getId());
            }
            outboxEvent.markSent();
            return true;
        } catch (Exception ex) {
            publishObs.error(ex);
            handleFailure(outboxEvent, ex);
            return false;
        } finally {
            publishObs.stop();
        }
    }

    private Message<?> buildMessage(OutboxEventEntity outboxEvent) {
        Map<String, Object> payload = outboxEvent.getPayloadJson();
        Map<String, Object> headers = outboxEvent.getHeadersJson();

        return MessageBuilder.withPayload(payload)
                .setHeader(KafkaHeaders.KEY, outboxEvent.getMessageKey())
                .copyHeaders(headers == null ? Map.of() : headers)
                .build();
    }

    private Map<String, String> toStringHeaders(Map<String, Object> headers) {
        if (headers == null || headers.isEmpty()) {
            return Map.of();
        }
        return headers.entrySet().stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey, entry -> entry.getValue() == null ? "" : String.valueOf(entry.getValue())));
    }

    private void handleFailure(OutboxEventEntity outboxEvent, Exception ex) {
        outboxEvent.bumpAttempts();
        if (outboxEvent.getAttempts() >= maxAttempts) {
            outboxEvent.markFailed();
            log.warn("Outbox permanently failed id={} attempts={}", outboxEvent.getId(), outboxEvent.getAttempts(), ex);
            Counter.builder(METRIC_DEAD_LETTER)
                    .description("Outbox events permanently failed after max delivery attempts")
                    .tag("eventType", outboxEvent.getEventType())
                    .tag("binding", outboxEvent.getBinding())
                    .register(meterRegistry)
                    .increment();
        } else {
            log.info("Outbox temporary failure id={} attempts={}", outboxEvent.getId(), outboxEvent.getAttempts(), ex);
        }
    }
}
