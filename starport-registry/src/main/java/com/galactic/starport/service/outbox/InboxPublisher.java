package com.galactic.starport.service.outbox;

import com.galactic.starport.repository.OutboxEventRepositoryFacade;
import com.galactic.starport.repository.OutboxFailureOutcome;
import com.galactic.starport.repository.PendingOutboxEvent;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.transport.Kind;
import io.micrometer.observation.transport.ReceiverContext;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
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
    private static final String METRIC_PENDING_EVENTS = "reservations.outbox.pending.events";
    // W3C (+ legacy B3) propagation headers persisted at append time. They feed the publish span's
    // ReceiverContext (parent linkage) but must NOT be forwarded to Kafka: the live producer span
    // injects a fresh traceparent so the telemetry consumer chains to the publish hop, not the
    // stale append span that finished ~poll-interval seconds earlier.
    private static final Set<String> PROPAGATION_HEADERS = Set.of("traceparent", "tracestate", "b3");
    private final OutboxEventRepositoryFacade outboxFacade;
    private final StreamBridge streamBridge;
    private final ObservationRegistry observationRegistry;
    private final MeterRegistry meterRegistry;
    private final int batchSize;
    private final int maxAttempts;
    private final AtomicLong pendingEventsCount = new AtomicLong(0);

    InboxPublisher(
            OutboxEventRepositoryFacade outboxFacade,
            StreamBridge streamBridge,
            ObservationRegistry observationRegistry,
            MeterRegistry meterRegistry,
            @Value("${outbox.batch-size:50}") int batchSize,
            @Value("${outbox.max-attempts:10}") int maxAttempts) {
        this.outboxFacade = outboxFacade;
        this.streamBridge = streamBridge;
        this.observationRegistry = observationRegistry;
        this.meterRegistry = meterRegistry;
        this.batchSize = batchSize;
        this.maxAttempts = maxAttempts;
        Gauge.builder(METRIC_PENDING_EVENTS, pendingEventsCount, AtomicLong::doubleValue)
                .description("Outbox events with status=PENDING — saturation indicator for the publish pipeline")
                .baseUnit("events")
                .register(meterRegistry);
    }

    @Scheduled(fixedDelayString = "${outbox.poll-interval-ms:10000}")
    @Transactional
    public void pollAndPublish() {
        Timer.Sample sample = Timer.start(meterRegistry);
        String outcome = "success";
        boolean anyFailure = false;
        try {
            List<PendingOutboxEvent> batch = outboxFacade.lockPendingBatch(batchSize);
            if (batch.isEmpty()) {
                outcome = "empty";
                return;
            }
            DistributionSummary.builder(METRIC_BATCH_SIZE)
                    .description("Outbox batch size fetched during poll")
                    .baseUnit("events")
                    .register(meterRegistry)
                    .record(batch.size());
            for (PendingOutboxEvent event : batch) {
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
                    .register(meterRegistry));
            try {
                pendingEventsCount.set(outboxFacade.countPending());
            } catch (Exception ex) {
                log.debug("Failed to refresh outbox pending count gauge", ex);
            }
        }
    }

    private boolean processSingleEvent(PendingOutboxEvent outboxEvent) {
        ReceiverContext<Map<String, String>> receiverCtx = new ReceiverContext<>(Map::get, Kind.CONSUMER);
        receiverCtx.setCarrier(toStringHeaders(outboxEvent.headersJson()));
        receiverCtx.setRemoteServiceName("kafka");
        Observation publishObs = Observation.createNotStarted(OBS_PUBLISH, () -> receiverCtx, observationRegistry)
                .lowCardinalityKeyValue("binding", outboxEvent.binding())
                .lowCardinalityKeyValue("eventType", outboxEvent.eventType())
                .highCardinalityKeyValue("outboxId", String.valueOf(outboxEvent.id()));
        publishObs.start();
        try (Observation.Scope scope = publishObs.openScope()) {
            Message<?> msg = buildMessage(outboxEvent);
            boolean sent = streamBridge.send(outboxEvent.binding(), msg);
            if (!sent) {
                throw new IllegalStateException("StreamBridge.send returned false for outboxId=" + outboxEvent.id());
            }
            outboxFacade.markSent(outboxEvent.id());
            return true;
        } catch (Exception ex) {
            publishObs.error(ex);
            handleFailure(outboxEvent, ex);
            return false;
        } finally {
            publishObs.stop();
        }
    }

    private Message<?> buildMessage(PendingOutboxEvent outboxEvent) {
        Map<String, Object> payload = outboxEvent.payloadJson();

        return MessageBuilder.withPayload(payload)
                .setHeader(KafkaHeaders.KEY, outboxEvent.messageKey())
                .copyHeaders(businessHeaders(outboxEvent.headersJson()))
                .build();
    }

    private Map<String, Object> businessHeaders(Map<String, Object> headers) {
        if (headers == null || headers.isEmpty()) {
            return Map.of();
        }
        return headers.entrySet().stream()
                .filter(e -> !PROPAGATION_HEADERS.contains(e.getKey().toLowerCase(Locale.ROOT)))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    private Map<String, String> toStringHeaders(Map<String, Object> headers) {
        if (headers == null || headers.isEmpty()) {
            return Map.of();
        }
        return headers.entrySet().stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey, entry -> entry.getValue() == null ? "" : String.valueOf(entry.getValue())));
    }

    private void handleFailure(PendingOutboxEvent outboxEvent, Exception ex) {
        OutboxFailureOutcome outcome = outboxFacade.recordFailure(outboxEvent.id(), maxAttempts);
        if (outcome.deadLettered()) {
            log.warn("Outbox permanently failed id={} attempts={}", outboxEvent.id(), outcome.attempts(), ex);
            Counter.builder(METRIC_DEAD_LETTER)
                    .description("Outbox events permanently failed after max delivery attempts")
                    .tag("eventType", outboxEvent.eventType())
                    .tag("binding", outboxEvent.binding())
                    .register(meterRegistry)
                    .increment();
        } else {
            log.info("Outbox temporary failure id={} attempts={}", outboxEvent.id(), outcome.attempts(), ex);
        }
    }
}
