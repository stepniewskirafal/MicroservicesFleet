package com.galactic.starport.service.outbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.galactic.starport.repository.OutboxEventEntity;
import com.galactic.starport.repository.OutboxEventJpaRepository;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.core.tck.MeterRegistryAssert;
import io.micrometer.observation.tck.TestObservationRegistry;
import io.micrometer.observation.tck.TestObservationRegistryAssert;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.messaging.Message;

@Execution(ExecutionMode.CONCURRENT)
class InboxPublisherObservabilityTest {

    private TestObservationRegistry observationRegistry;
    private SimpleMeterRegistry meterRegistry;
    private OutboxEventJpaRepository repo;
    private StreamBridge streamBridge;

    private static final int BATCH_SIZE = 50;
    private static final int MAX_ATTEMPTS = 10;

    private InboxPublisher publisher;

    @BeforeEach
    void setUp() {
        observationRegistry = TestObservationRegistry.create();
        meterRegistry = new SimpleMeterRegistry();
        repo = mock(OutboxEventJpaRepository.class);
        streamBridge = mock(StreamBridge.class);
        publisher = new InboxPublisher(repo, streamBridge, observationRegistry, meterRegistry, BATCH_SIZE, MAX_ATTEMPTS);
    }

    @Test
    void pollAndPublishEmitsObservationAndRecordsMetricsOnSuccessPath() {
        OutboxEventEntity e = new OutboxEventEntity();
        e.setId(10L);
        e.setBinding("reservations-out");
        e.setEventType("ReservationConfirmed");
        e.setMessageKey("10");
        e.setPayloadJson(Map.of("reservationId", 10));
        e.setHeadersJson(Map.of("contentType", "application/json"));

        when(repo.lockBatchPending(BATCH_SIZE)).thenReturn(List.of(e));
        when(streamBridge.send(eq("reservations-out"), any(Message.class))).thenReturn(true);

        publisher.pollAndPublish();

        TestObservationRegistryAssert.assertThat(observationRegistry)
                .hasObservationWithNameEqualTo("reservations.inbox.publish")
                .that()
                .hasBeenStarted()
                .hasBeenStopped()
                .hasLowCardinalityKeyValue("binding", "reservations-out")
                .hasLowCardinalityKeyValue("eventType", "ReservationConfirmed");

        MeterRegistryAssert.assertThat(meterRegistry)
                .hasMeterWithName("reservations.inbox.poll.duration")
                .hasMeterWithName("reservations.inbox.poll.batch.size");

        Timer t = meterRegistry
                .get("reservations.inbox.poll.duration")
                .tags("outcome", "success", "batchSize", "1")
                .timer();
        assertEquals(1, t.count());

        var s = meterRegistry.get("reservations.inbox.poll.batch.size").summary();
        assertEquals(1, s.count());
        assertEquals(1d, s.totalAmount(), 0.001);
        assertEquals("events", s.getId().getBaseUnit());
    }

    @Test
    void pollAndPublishRecordsPollDurationWithOutcomeErrorWhenStreamBridgeReturnsFalse() {
        OutboxEventEntity e = new OutboxEventEntity();
        e.setId(11L);
        e.setBinding("reservations-out");
        e.setEventType("ReservationConfirmed");
        e.setMessageKey("11");
        e.setPayloadJson(Map.of("reservationId", 11));
        e.setHeadersJson(Map.of("contentType", "application/json"));

        when(repo.lockBatchPending(BATCH_SIZE)).thenReturn(List.of(e));
        when(streamBridge.send(eq("reservations-out"), any(Message.class))).thenReturn(false);

        publisher.pollAndPublish();

        TestObservationRegistryAssert.assertThat(observationRegistry)
                .hasObservationWithNameEqualTo("reservations.inbox.publish")
                .that()
                .hasError()
                .hasBeenStopped()
                .hasLowCardinalityKeyValue("binding", "reservations-out")
                .hasLowCardinalityKeyValue("eventType", "ReservationConfirmed");

        Timer t = meterRegistry
                .get("reservations.inbox.poll.duration")
                .tags("outcome", "error", "batchSize", "1")
                .timer();
        assertEquals(1, t.count());

        var s = meterRegistry.get("reservations.inbox.poll.batch.size").summary();
        assertEquals(1, s.count());
        assertEquals(1d, s.totalAmount(), 0.001);
    }

    @Test
    void pollAndPublishRecordsPollDurationWithOutcomeEmptyWhenNoEventsFound() {
        when(repo.lockBatchPending(BATCH_SIZE)).thenReturn(List.of());

        publisher.pollAndPublish();

        Timer t = meterRegistry
                .get("reservations.inbox.poll.duration")
                .tags("outcome", "empty", "batchSize", "0")
                .timer();
        assertEquals(1, t.count());

        var meters = meterRegistry.getMeters().stream()
                .filter(m -> "reservations.inbox.poll.batch.size".equals(m.getId().getName()))
                .toList();
        assert meters.isEmpty() : "Batch size summary should not be created for empty batch";
    }
}
