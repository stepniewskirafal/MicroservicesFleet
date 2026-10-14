package com.galactic.fleet.acceptance;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.galactic.fleet.acceptance.support.Fleet;
import com.galactic.fleet.acceptance.support.TopicProbe;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Sensor telemetry: readings arrive on {@code telemetry.raw}, alerts leave on {@code telemetry.alerts}.
 *
 * <p>Only the stateless rules are frozen here (validation, enrichment, threshold breach). The
 * statistical "3 sigma" rule keeps a rolling window in the memory of whichever instance consumed the
 * readings, so it cannot be observed deterministically from outside; it stays covered by the
 * in-process tests of the telemetry code.
 */
@DisplayName("Telemetry: raw sensor readings in, anomaly alerts out")
class TelemetryPipelineSystemTest {

    private static final String RAW = "telemetry.raw";
    private static final String ALERTS = "telemetry.alerts";
    private static final Duration PATIENCE = Duration.ofSeconds(40);

    @Test
    @DisplayName("a reading above the sensor's upper threshold raises a CRITICAL alert")
    void reading_above_upper_threshold_raises_critical_alert() throws Exception {
        String shipId = uniqueShip();
        try (TopicProbe alerts = TopicProbe.atEndOf(ALERTS)) {
            TopicProbe.publish(RAW, shipId, reading(shipId, "TEMPERATURE", 350.5));

            JsonNode alert = json(alerts.await(r -> mentions(r, shipId), PATIENCE));

            assertThat(alert.path("shipId").asText()).isEqualTo(shipId);
            assertThat(alert.path("sensorType").asText()).isEqualTo("TEMPERATURE");
            assertThat(alert.path("severity").asText()).isEqualTo("CRITICAL");
            assertThat(alert.path("currentValue").asDouble()).isEqualTo(350.5);
            assertThat(alert.path("threshold").asDouble()).isEqualTo(300.0);
            assertThat(alert.path("description").asText()).contains("exceeded upper threshold");
            assertThat(alert.path("detectedAt").isMissingNode()).isFalse();
        }
    }

    @Test
    @DisplayName("a reading below the sensor's lower threshold raises a CRITICAL alert")
    void reading_below_lower_threshold_raises_critical_alert() throws Exception {
        String shipId = uniqueShip();
        try (TopicProbe alerts = TopicProbe.atEndOf(ALERTS)) {
            TopicProbe.publish(RAW, shipId, reading(shipId, "FUEL_LEVEL", 2.0));

            JsonNode alert = json(alerts.await(r -> mentions(r, shipId), PATIENCE));

            assertThat(alert.path("sensorType").asText()).isEqualTo("FUEL_LEVEL");
            assertThat(alert.path("severity").asText()).isEqualTo("CRITICAL");
            assertThat(alert.path("currentValue").asDouble()).isEqualTo(2.0);
            assertThat(alert.path("threshold").asDouble()).isEqualTo(5.0);
            assertThat(alert.path("description").asText()).contains("below lower threshold");
        }
    }

    @Test
    @DisplayName("an unregistered ship is enriched as class 'Unknown' in sector 'Unknown'")
    void unknown_ship_is_enriched_with_unknown_class_and_sector() throws Exception {
        String shipId = uniqueShip();
        try (TopicProbe alerts = TopicProbe.atEndOf(ALERTS)) {
            TopicProbe.publish(RAW, shipId, reading(shipId, "RADIATION", 1500.0));

            JsonNode alert = json(alerts.await(r -> mentions(r, shipId), PATIENCE));

            assertThat(alert.path("shipClass").asText()).isEqualTo("Unknown");
            assertThat(alert.path("currentSector").asText()).isEqualTo("Unknown");
        }
    }

    @Test
    @DisplayName("a registered ship (SHIP-003) is enriched with its class and current sector")
    void registered_ship_is_enriched_with_class_and_sector() throws Exception {
        // SHIP-003 is shared reference data, so the alert is recognised by a value no one else sends.
        double fingerprint = 400.0 + (System.nanoTime() % 100_000) / 1000.0;
        try (TopicProbe alerts = TopicProbe.atEndOf(ALERTS)) {
            TopicProbe.publish(RAW, "SHIP-003", reading("SHIP-003", "TEMPERATURE", fingerprint));

            JsonNode alert = json(alerts.await(
                    r -> mentions(r, "SHIP-003") && json(r).path("currentValue").asDouble() == fingerprint, PATIENCE));

            assertThat(alert.path("shipClass").asText()).isEqualTo("Cruiser");
            assertThat(alert.path("currentSector").asText()).isEqualTo("Proxima-B");
        }
    }

    @Test
    @DisplayName("a reading inside the thresholds raises no alert")
    void reading_within_thresholds_raises_no_alert() {
        String shipId = uniqueShip();
        try (TopicProbe alerts = TopicProbe.atEndOf(ALERTS)) {
            TopicProbe.publish(RAW, shipId, reading(shipId, "TEMPERATURE", 21.0));

            List<ConsumerRecord<String, String>> raised = alerts.collect(r -> mentions(r, shipId), Duration.ofSeconds(8));

            assertThat(raised).isEmpty();
        }
    }

    @Test
    @DisplayName("an invalid reading is dropped; the pipeline keeps processing the next valid one")
    void invalid_reading_is_dropped_and_pipeline_keeps_working() throws Exception {
        String shipId = uniqueShip();
        try (TopicProbe alerts = TopicProbe.atEndOf(ALERTS)) {
            // Unknown sensor type with an absurd value: would be an alert if it were not rejected.
            TopicProbe.publish(RAW, shipId, reading(shipId, "WARP_CORE", 999_999.0));
            // Missing timestamp: also rejected.
            TopicProbe.publish(
                    RAW,
                    shipId,
                    """
                    {"shipId":"%s","sensorType":"TEMPERATURE","value":999.0,"metadata":{}}"""
                            .formatted(shipId));
            // A valid breach afterwards proves the pipeline survived both and is not just slow.
            TopicProbe.publish(RAW, shipId, reading(shipId, "OXYGEN_LEVEL", 3.0));

            JsonNode alert = json(alerts.await(r -> mentions(r, shipId), PATIENCE));
            assertThat(alert.path("sensorType").asText()).isEqualTo("OXYGEN_LEVEL");

            List<ConsumerRecord<String, String>> more = alerts.collect(r -> mentions(r, shipId), Duration.ofSeconds(6));
            assertThat(more).as("the two invalid readings must not produce alerts").isEmpty();
        }
    }

    private static String uniqueShip() {
        return "ACC-" + UUID.randomUUID().toString().substring(0, 12).toUpperCase();
    }

    private static String reading(String shipId, String sensorType, double value) {
        return """
                {"shipId":"%s","sensorType":"%s","value":%s,"timestamp":"%s","metadata":{}}"""
                .formatted(shipId, sensorType, value, Instant.now());
    }

    private static boolean mentions(ConsumerRecord<String, String> record, String shipId) {
        return record.value() != null && record.value().contains("\"" + shipId + "\"");
    }

    private static JsonNode json(ConsumerRecord<String, String> record) {
        try {
            return Fleet.JSON.readTree(record.value());
        } catch (Exception e) {
            throw new AssertionError("Not JSON on " + record.topic() + ": " + record.value(), e);
        }
    }
}
