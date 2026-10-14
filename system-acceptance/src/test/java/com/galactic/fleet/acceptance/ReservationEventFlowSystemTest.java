package com.galactic.fleet.acceptance;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.galactic.fleet.acceptance.support.Fleet;
import com.galactic.fleet.acceptance.support.Fleet.Reply;
import com.galactic.fleet.acceptance.support.Fleet.Vessel;
import com.galactic.fleet.acceptance.support.TopicProbe;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What the rest of the galaxy hears after a reservation is confirmed. One HTTP request produces up
 * to two Kafka messages — the computed telemetry; their topics, field names and value formats are a
 * contract with consumers outside this codebase.
 *
 * <pre>
 * POST reservation ─┬─► telemetry.enriched-reservations
 *                   └─► telemetry.enriched-routes      (only when a route was requested)
 * </pre>
 *
 * <p>Changed deliberately by ADR-0046: the raw topics {@code starport.reservations} and
 * {@code starport.route-planned} no longer exist. They were the wire between the three services of
 * the original fleet; inside one application the modules exchange an application event, and only
 * computed telemetry leaves the system. Everything asserted about the two enriched topics is
 * unchanged from the frozen baseline.
 */
@DisplayName("Events published after a confirmed reservation")
class ReservationEventFlowSystemTest {

    private static final String ENRICHED_RESERVATIONS = "telemetry.enriched-reservations";
    private static final String ENRICHED_ROUTES = "telemetry.enriched-routes";
    private static final Duration PATIENCE = Duration.ofSeconds(60);

    @Test
    @DisplayName("a reservation with a route is announced on both enriched topics, in one trace")
    void reservation_with_route_is_announced_on_both_enriched_topics() {
        Instant startAt = Fleet.freshSlotAt(20);
        try (TopicProbe enrichedReservations = TopicProbe.atEndOf(ENRICHED_RESERVATIONS);
                TopicProbe enrichedRoutes = TopicProbe.atEndOf(ENRICHED_ROUTES)) {

            Reply reply = Fleet.reserve("HOTH", Fleet.reservationBody(Vessel.FREIGHTER, startAt, 3, "TATOO"));
            assertThat(reply.status()).as(reply.rawBody()).isEqualTo(201);
            long reservationId = reply.body().path("reservationId").asLong();
            String bay = reply.body().path("bayNumber").asText();
            JsonNode route = reply.body().path("route");
            String routeCode = route.path("routeCode").asText();

            // 1. telemetry.enriched-reservations — the reservation plus what telemetry adds
            ConsumerRecord<String, String> enrichedReservation = enrichedReservations.await(
                    r -> json(r).path("reservationId").asLong() == reservationId, PATIENCE);
            JsonNode enriched = json(enrichedReservation);
            // Delivery is at-least-once; the key is what a consumer deduplicates on (ADR-0048).
            assertThat(enrichedReservation.key()).isEqualTo(String.valueOf(reservationId));
            assertThat(enriched.path("eventType").asText()).isEqualTo("RESERVATION_CONFIRMED");
            assertThat(enriched.path("status").asText()).isEqualTo("CONFIRMED");
            assertThat(enriched.path("starportCode").asText()).isEqualTo("HOTH");
            assertThat(enriched.path("dockingBayLabel").asText()).isEqualTo(bay);
            assertThat(enriched.path("customerCode").asText()).isEqualTo("CUST-002");
            assertThat(enriched.path("shipCode").asText()).isEqualTo("FALCON-002");
            assertThat(enriched.path("routeCode").asText()).isEqualTo(routeCode);
            assertThat(Instant.parse(enriched.path("startAt").asText())).isEqualTo(startAt);
            assertThat(Instant.parse(enriched.path("endAt").asText())).isEqualTo(startAt.plus(3, ChronoUnit.HOURS));
            assertThat(enriched.path("feeCharged").decimalValue()).isEqualByComparingTo("360");
            assertThat(enriched.path("durationHours").asLong()).isEqualTo(3);
            assertThat(enriched.path("processedBy").asText()).isEqualTo("telemetry-pipeline");
            assertThat(Instant.parse(enriched.path("processedAt").asText())).isBeforeOrEqualTo(Instant.now());

            // 2. telemetry.enriched-routes — the planned route (values identical to the HTTP
            // response) plus a risk level
            ConsumerRecord<String, String> enrichedRoute = enrichedRoutes.await(
                    r -> routeCode.equals(json(r).path("routeId").asText()), PATIENCE);
            JsonNode enrichedRouteEvent = json(enrichedRoute);
            assertThat(enrichedRoute.key()).isEqualTo(routeCode);
            double risk = route.path("riskScore").asDouble();
            assertThat(enrichedRouteEvent.path("eventType").asText()).isEqualTo("ROUTE_PLANNED");
            assertThat(enrichedRouteEvent.path("originPortId").asText()).isEqualTo("TATOO");
            assertThat(enrichedRouteEvent.path("destinationPortId").asText()).isEqualTo("HOTH");
            assertThat(enrichedRouteEvent.path("shipClass").asText()).isEqualTo("FREIGHTER");
            assertThat(enrichedRouteEvent.path("etaHours").asDouble())
                    .isEqualTo(route.path("etaHours").asDouble());
            assertThat(Instant.parse(enrichedRouteEvent.path("plannedAt").asText()))
                    .isBeforeOrEqualTo(Instant.now());
            assertThat(enrichedRouteEvent.path("riskScore").asDouble()).isEqualTo(risk);
            assertThat(enrichedRouteEvent.path("riskLevel").asText())
                    .as("risk < 0.3 is LOW, < 0.7 is MEDIUM, otherwise HIGH")
                    .isEqualTo(risk < 0.3 ? "LOW" : risk < 0.7 ? "MEDIUM" : "HIGH");
            assertThat(enrichedRouteEvent.path("processedBy").asText()).isEqualTo("telemetry-pipeline");

            // One request, one trace: every message carries the W3C trace context of the HTTP call,
            // which is what lets an operator follow a reservation across the asynchronous hop.
            String traceId = traceIdOf(enrichedReservation);
            assertThat(traceId).matches("[0-9a-f]{32}");
            assertThat(traceIdOf(enrichedRoute)).isEqualTo(traceId);
        }
    }

    @Test
    @DisplayName("a reservation without a route is announced without a route code")
    void reservation_without_route_is_announced_without_route_code() {
        try (TopicProbe enrichedReservations = TopicProbe.atEndOf(ENRICHED_RESERVATIONS)) {

            Reply reply = Fleet.reserve("NABOO", Fleet.reservationBody(Vessel.SCOUT, Fleet.freshSlotAt(20), 2, null));
            assertThat(reply.status()).as(reply.rawBody()).isEqualTo(201);
            long reservationId = reply.body().path("reservationId").asLong();

            JsonNode enriched = json(enrichedReservations.await(
                    r -> json(r).path("reservationId").asLong() == reservationId, PATIENCE));
            assertThat(enriched.path("eventType").asText()).isEqualTo("RESERVATION_CONFIRMED");
            assertThat(enriched.path("status").asText()).isEqualTo("CONFIRMED");
            assertThat(enriched.path("durationHours").asLong()).isEqualTo(2);
            assertThat(enriched.path("routeCode").isNull() || enriched.path("routeCode").isMissingNode())
                    .as("no route was requested")
                    .isTrue();
        }
    }

    @Test
    @DisplayName("a refused request announces nothing")
    void refused_request_announces_nothing() {
        // A refusal has no reservation id to look for, so the proof is by marker: the ghost ship's
        // code must never appear on the enriched reservations topic.
        String ghostShip = "GHOST-" + System.nanoTime();
        Vessel ghost = new Vessel("CUST-001", ghostShip, "SCOUT");
        try (TopicProbe reservations = TopicProbe.atEndOf(ENRICHED_RESERVATIONS)) {

            Reply reply = Fleet.reserve("TATOO", Fleet.reservationBody(ghost, Fleet.freshSlotAt(20), 1, null));
            assertThat(reply.status()).as(reply.rawBody()).isEqualTo(404);

            assertThat(reservations.collect(
                            r -> r.value() != null && r.value().contains(ghostShip), Duration.ofSeconds(8)))
                    .isEmpty();
        }
    }

    /** W3C traceparent: {@code 00-<32 hex trace id>-<16 hex span id>-<flags>}. */
    private static String traceIdOf(ConsumerRecord<String, String> record) {
        Header header = record.headers().lastHeader("traceparent");
        assertThat(header).as("traceparent header on " + record.topic()).isNotNull();
        String[] parts = new String(header.value(), StandardCharsets.UTF_8).replace("\"", "").split("-");
        return parts.length > 1 ? parts[1] : "";
    }

    private static JsonNode json(ConsumerRecord<String, String> record) {
        try {
            return Fleet.JSON.readTree(record.value());
        } catch (Exception e) {
            throw new AssertionError("Not JSON on " + record.topic() + ": " + record.value(), e);
        }
    }
}
