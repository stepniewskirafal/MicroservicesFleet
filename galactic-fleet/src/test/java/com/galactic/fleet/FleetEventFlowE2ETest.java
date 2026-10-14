package com.galactic.fleet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import com.galactic.fleet.booking.BaseAcceptanceTest;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceAccessMode;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.stream.binder.test.OutputDestination;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.messaging.Message;

/**
 * The modules working together inside one application context (ADR-0046):
 *
 * <pre>
 * POST reservation ──► booking ──call──► routeplanning               (pure computation, before the transaction)
 *                           │
 *                           └─ one transaction: reservation + ReservationConfirmed in event_publication
 *                                      │ after commit, another thread
 *                                      ▼
 *                                 activityfeed ──► telemetry.enriched-reservations
 *                                              └─► telemetry.enriched-routes      (only with a route)
 * </pre>
 *
 * The broker is the in-memory test binder, so this is the in-build twin of
 * {@code ReservationEventFlowSystemTest} from the black-box suite (which needs the running stack).
 */
@ResourceLock(value = "DB_TRUNCATE", mode = ResourceAccessMode.READ)
// Both event-flow tests drain the same in-memory topic; run them one at a time.
@ResourceLock("ENRICHED_TOPICS")
class FleetEventFlowE2ETest extends BaseAcceptanceTest {

    private static final Duration PATIENCE = Duration.ofSeconds(20);

    @Autowired
    OutputDestination output;

    @Test
    void one_reservation_with_a_route_produces_both_computed_records() throws Exception {
        String starport = "FLOW-DEST";
        seedDefaultReservationFixture(
                starport,
                Map.of(
                        "originCode", "FLOW-ORIGIN",
                        "customerCode", "CUST-FLOW",
                        "shipCode", "SS-FLOW-01"));
        Instant start = Instant.parse("2031-03-01T20:00:00Z");
        Map<String, Object> request = makePayload(Map.of(
                "customerCode",
                "CUST-FLOW",
                "shipCode",
                "SS-FLOW-01",
                "originPortId",
                "FLOW-ORIGIN",
                "startAt",
                start.toString(),
                "endAt",
                start.plusSeconds(2 * 3600).toString(),
                "requestRoute",
                true));

        ResponseEntity<String> response = postReservation(starport, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode body = objectMapper.readTree(response.getBody());
        long reservationId = body.path("reservationId").asLong();
        String routeCode = body.path("route").path("routeCode").asText();
        assertThat(routeCode).startsWith("ROUTE-");

        // Both records are produced after the response, by the listener on another thread.
        JsonNode enrichedReservation = awaitMessage(
                "telemetry.enriched-reservations", m -> m.path("reservationId").asLong() == reservationId);
        assertThat(enrichedReservation.path("eventType").asText()).isEqualTo("RESERVATION_CONFIRMED");
        assertThat(enrichedReservation.path("status").asText()).isEqualTo("CONFIRMED");
        assertThat(enrichedReservation.path("starportCode").asText()).isEqualTo(starport);
        assertThat(enrichedReservation.path("routeCode").asText()).isEqualTo(routeCode);
        assertThat(Instant.parse(enrichedReservation.path("startAt").asText())).isEqualTo(start);
        assertThat(enrichedReservation.path("durationHours").asLong()).isEqualTo(2);
        assertThat(enrichedReservation.path("processedBy").asText()).isEqualTo("telemetry-pipeline");

        JsonNode enrichedRoute = awaitMessage(
                "telemetry.enriched-routes",
                m -> routeCode.equals(m.path("routeId").asText()));
        assertThat(enrichedRoute.path("eventType").asText()).isEqualTo("ROUTE_PLANNED");
        assertThat(enrichedRoute.path("originPortId").asText()).isEqualTo("FLOW-ORIGIN");
        assertThat(enrichedRoute.path("destinationPortId").asText()).isEqualTo(starport);
        assertThat(enrichedRoute.path("shipClass").asText()).isEqualTo("SCOUT");
        assertThat(enrichedRoute.path("etaHours").asDouble())
                .isEqualTo(body.path("route").path("etaHours").asDouble());
        assertThat(enrichedRoute.path("riskLevel").asText()).isIn("LOW", "MEDIUM", "HIGH");
        assertThat(Instant.parse(enrichedRoute.path("plannedAt").asText())).isBeforeOrEqualTo(Instant.now());
        assertThat(enrichedRoute.path("processedBy").asText()).isEqualTo("telemetry-pipeline");

        // Once the listener has returned, the registry removes the publication (completion-mode:
        // delete, ADR-0048) — event_publication holds only what still has to be delivered.
        await().atMost(PATIENCE).untilAsserted(() -> assertThat(jdbc.queryForObject(
                        "select count(*) from event_publication where serialized_event like ?",
                        Long.class,
                        "%\"reservationId\":" + reservationId + ",%"))
                .isZero());
    }

    /** Other test classes share this context and its topics, so skip messages that are not ours. */
    private JsonNode awaitMessage(String topic, Predicate<JsonNode> mine) throws Exception {
        Instant deadline = Instant.now().plus(PATIENCE);
        while (Instant.now().isBefore(deadline)) {
            Message<byte[]> message = output.receive(500, topic);
            if (message != null) {
                JsonNode json = objectMapper.readTree(message.getPayload());
                if (mine.test(json)) {
                    return json;
                }
            }
        }
        throw new AssertionError("No matching message on " + topic + " within " + PATIENCE);
    }
}
