package com.galactic.fleet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import com.galactic.fleet.booking.BaseAcceptanceTest;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceAccessMode;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.stream.binder.test.OutputDestination;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.Message;

/**
 * An event whose listener did not finish is delivered again (ADR-0043).
 *
 * <p>The starting point is what a crash or a broker outage leaves behind: a row in
 * {@code event_publication} with no completion date, older than the re-delivery threshold. The test
 * writes such a row by hand — exactly as the registry serialises it — and runs one round of
 * {@link EventPublicationMaintenance}. The real registry, the real listener and a real Postgres
 * (advisory lock included) do the rest; only the broker is the in-memory test binder.
 */
@ResourceLock(value = "DB_TRUNCATE", mode = ResourceAccessMode.READ)
// Both event-flow tests drain the same in-memory topic; run them one at a time.
@ResourceLock("ENRICHED_TOPICS")
class EventRedeliveryE2ETest extends BaseAcceptanceTest {

    private static final Duration PATIENCE = Duration.ofSeconds(20);
    private static final String LISTENER_ID = "com.galactic.fleet.activityfeed.pipeline.FleetEventListener"
            + ".on(com.galactic.fleet.booking.events.ReservationConfirmed)";
    private static final String EVENT_TYPE = "com.galactic.fleet.booking.events.ReservationConfirmed";

    @Autowired
    OutputDestination output;

    @Autowired
    EventPublicationMaintenance maintenance;

    @Autowired
    MeterRegistry meterRegistry;

    @Test
    void an_incomplete_publication_is_redelivered_and_then_removed() throws Exception {
        long reservationId = 900_000_000L + (System.nanoTime() % 1_000_000L);
        UUID publicationId = UUID.randomUUID();
        // No "traceparent" field: rows written before ADR-0043 look like this and must still load.
        String event =
                """
                {"reservationId":%d,"status":"CONFIRMED","starportCode":"HOTH","dockingBayLabel":"F-001",\
                "customerCode":"CUST-002","shipCode":"FALCON-002","startAt":"2337-03-20T20:00:00Z",\
                "endAt":"2337-03-20T23:00:00Z","feeCharged":360,"producedAt":"2026-11-23T20:40:27Z","route":null}"""
                        .formatted(reservationId);
        jdbc.update(
                "insert into event_publication (id, listener_id, event_type, serialized_event, publication_date)"
                        + " values (?, ?, ?, ?, now() - interval '5 minutes')",
                publicationId,
                LISTENER_ID,
                EVENT_TYPE,
                event);

        maintenance.redeliverIncompletePublications();

        assertThat(meterRegistry
                        .get(EventPublicationMaintenance.METRIC_INCOMPLETE)
                        .gauge()
                        .value())
                .as("the round saw the waiting publication")
                .isGreaterThanOrEqualTo(1.0);
        assertThat(meterRegistry
                        .get(EventPublicationMaintenance.METRIC_OLDEST_AGE)
                        .gauge()
                        .value())
                .isGreaterThanOrEqualTo(Duration.ofMinutes(4).toSeconds());

        Message<byte[]> redelivered = awaitReservationRecord(reservationId);
        JsonNode enriched = objectMapper.readTree(redelivered.getPayload());
        assertThat(enriched.path("eventType").asText()).isEqualTo("RESERVATION_CONFIRMED");
        assertThat(enriched.path("durationHours").asLong()).isEqualTo(3);
        assertThat(String.valueOf(redelivered.getHeaders().get(KafkaHeaders.KEY)))
                .as("the key a consumer deduplicates on")
                .isEqualTo(String.valueOf(reservationId));

        // Delivered, so the registry removes the row — and the next round finds nothing of ours.
        await().atMost(PATIENCE).untilAsserted(() -> assertThat(jdbc.queryForObject(
                        "select count(*) from event_publication where id = ?", Long.class, publicationId))
                .isZero());
    }

    @Test
    void a_publication_younger_than_the_threshold_is_left_alone() {
        // It may simply still be in flight; re-submitting it would send the record twice.
        UUID publicationId = UUID.randomUUID();
        long reservationId = 800_000_000L + (System.nanoTime() % 1_000_000L);
        jdbc.update(
                "insert into event_publication (id, listener_id, event_type, serialized_event, publication_date)"
                        + " values (?, ?, ?, ?, now())",
                publicationId,
                LISTENER_ID,
                EVENT_TYPE,
                "{\"reservationId\":%d,\"status\":\"CONFIRMED\",\"route\":null}".formatted(reservationId));
        try {
            maintenance.redeliverIncompletePublications();

            assertThat(jdbc.queryForObject(
                            "select count(*) from event_publication where id = ?", Long.class, publicationId))
                    .isEqualTo(1L);
        } finally {
            jdbc.update("delete from event_publication where id = ?", publicationId);
        }
    }

    /** Other test classes share this context and its topics, so skip records that are not ours. */
    private Message<byte[]> awaitReservationRecord(long reservationId) throws Exception {
        Instant deadline = Instant.now().plus(PATIENCE);
        while (Instant.now().isBefore(deadline)) {
            Message<byte[]> message = output.receive(500, "telemetry.enriched-reservations");
            if (message != null
                    && objectMapper
                                    .readTree(message.getPayload())
                                    .path("reservationId")
                                    .asLong()
                            == reservationId) {
                return message;
            }
        }
        throw new AssertionError("No enriched record for reservation " + reservationId + " within " + PATIENCE);
    }
}
