package com.galactic.fleet.acceptance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import com.galactic.fleet.acceptance.support.Fleet;
import com.galactic.fleet.acceptance.support.Fleet.Reply;
import com.galactic.fleet.acceptance.support.Fleet.Vessel;
import com.galactic.fleet.acceptance.support.TopicProbe;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Metrics are an interface too: dashboards and alert rules are written against metric NAMES. This
 * class freezes the names the provisioned Grafana dashboards query, as Prometheus sees them — it
 * says nothing about which process or how many processes expose them, so the {@code job} and
 * {@code instance} labels are deliberately not part of the contract.
 */
@DisplayName("Observability: the metrics dashboards are built on")
class ObservabilitySystemTest {

    private static final Duration SCRAPE_PATIENCE = Duration.ofSeconds(60);

    @BeforeAll
    static void produceOneOfEverything() {
        // Most meters appear only after first use: one reservation with a route and one sensor
        // reading make every business path emit at least once.
        Reply reply =
                Fleet.reserve("TATOO", Fleet.reservationBody(Vessel.CRUISER, Fleet.freshSlotAt(20), 1, "ALDER"));
        assertThat(reply.status()).as(reply.rawBody()).isEqualTo(201);
        TopicProbe.publish(
                "telemetry.raw",
                "SHIP-001",
                """
                {"shipId":"SHIP-001","sensorType":"TEMPERATURE","value":20.0,"timestamp":"%s","metadata":{}}"""
                        .formatted(Instant.now()));
    }

    @Test
    @DisplayName("every metric name used by the dashboards is known to Prometheus")
    void dashboard_metric_names_exist() {
        Set<String> required = Set.of(
                // reservations
                "reservations_total",
                "reservations_bay_allocate_seconds_bucket",
                "reservations_fees_calculate_seconds_bucket",
                "reservations_fees_calculated_amount_cr_sum",
                "reservations_route_plan_seconds_bucket",
                "reservations_docks_capacity",
                "reservations_docks_occupied",
                "reservations_docks_occupied_ratio",
                // (ADR-0046: the outbox relay and its three metrics are gone — the Event Publication
                // Registry delivers ReservationConfirmed; its lag is events_reservation_lag below.)
                // route planning
                "routes_plan_seconds_bucket",
                "routes_planned_count_total",
                "routes_risk_score_count",
                // event enrichment
                "events_reservation_received_total",
                "events_reservation_enriched_total",
                "events_reservation_lag_seconds_count",
                "events_route_received_total",
                "events_route_enriched_total",
                // delivery after commit (ADR-0048): what is still waiting in event_publication
                "events_publications_incomplete_events",
                "events_publications_oldest_age_seconds",
                // sensor telemetry
                "telemetry_messages_received_total",
                "telemetry_messages_invalid_total",
                "telemetry_anomalies_detected_total",
                "telemetry_messages_key_mismatch_total");

        await().atMost(SCRAPE_PATIENCE).pollInterval(Duration.ofSeconds(3)).untilAsserted(() -> {
            Set<String> missing = new HashSet<>(required);
            missing.removeAll(knownMetricNames());
            assertThat(missing).as("metric names missing in Prometheus").isEmpty();
        });
    }

    @Test
    @DisplayName("a confirmed reservation is counted in reservations_total{outcome=\"confirmed\"}")
    void confirmed_reservation_is_counted() {
        String query = "sum(reservations_total{outcome=\"confirmed\"})";
        double before = scalar(query);

        Reply reply = Fleet.reserve("DAGO", Fleet.reservationBody(Vessel.SCOUT, Fleet.freshSlotAt(20), 1, null));
        assertThat(reply.status()).as(reply.rawBody()).isEqualTo(201);

        await().atMost(SCRAPE_PATIENCE)
                .pollInterval(Duration.ofSeconds(2))
                .untilAsserted(() -> assertThat(scalar(query)).isGreaterThanOrEqualTo(before + 1));
    }

    @Test
    @DisplayName("a refused reservation is counted under its own outcome, not as confirmed")
    void refused_reservation_is_not_counted_as_confirmed() {
        // Fill one window of CRUISER bays at a quiet port, then ask once more: the refusal must show
        // up as outcome="no_capacity".
        String query = "sum(reservations_total{outcome=\"no_capacity\"})";
        double before = scalar(query);

        String body = Fleet.reservationBody(Vessel.CRUISER, Fleet.freshSlotAt(20), 1, null);
        Reply reply = Fleet.reserve("BESPIN", body);
        for (int i = 0; i < 500 && reply.status() == 201; i++) {
            reply = Fleet.reserve("BESPIN", body);
        }
        assertThat(reply.status()).as(reply.rawBody()).isEqualTo(409);

        await().atMost(SCRAPE_PATIENCE)
                .pollInterval(Duration.ofSeconds(2))
                .untilAsserted(() -> assertThat(scalar(query)).isGreaterThanOrEqualTo(before + 1));
    }

    private static Set<String> knownMetricNames() {
        Reply reply = Fleet.get(Fleet.PROMETHEUS + "/api/v1/label/__name__/values");
        assertThat(reply.status()).as(reply.rawBody()).isEqualTo(200);
        Set<String> names = new HashSet<>();
        reply.body().path("data").forEach(name -> names.add(name.asText()));
        return names;
    }

    /** Value of an instant query that yields one series; 0 when the series does not exist yet. */
    private static double scalar(String promQl) {
        Reply reply = Fleet.get(
                Fleet.PROMETHEUS + "/api/v1/query?query=" + URLEncoder.encode(promQl, StandardCharsets.UTF_8));
        assertThat(reply.status()).as(reply.rawBody()).isEqualTo(200);
        JsonNode result = reply.body().path("data").path("result");
        return result.isEmpty() ? 0.0 : result.get(0).path("value").get(1).asDouble();
    }
}
