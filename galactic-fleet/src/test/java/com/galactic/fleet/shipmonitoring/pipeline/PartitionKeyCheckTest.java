package com.galactic.fleet.shipmonitoring.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import com.galactic.fleet.shipmonitoring.model.RawTelemetry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The aggregation windows live in one instance's memory, so every reading of a ship must reach the
 * same instance — true only when {@code telemetry.raw} is keyed by ship id (ADR-0048).
 */
class PartitionKeyCheckTest {

    private SimpleMeterRegistry meterRegistry;
    private PartitionKeyCheck check;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        check = new PartitionKeyCheck(meterRegistry);
    }

    @Test
    void a_key_equal_to_the_ship_id_is_fine_as_bytes_or_as_text() {
        check.check("SHIP-001".getBytes(StandardCharsets.UTF_8), aReading("SHIP-001"));
        check.check("SHIP-001", aReading("SHIP-001"));

        assertThat(mismatches()).isZero();
    }

    @Test
    void a_missing_key_is_counted() {
        check.check(null, aReading("SHIP-001"));

        assertThat(mismatches()).isEqualTo(1.0);
    }

    @Test
    void a_key_that_is_not_the_ship_id_is_counted() {
        check.check("sensor-7".getBytes(StandardCharsets.UTF_8), aReading("SHIP-001"));

        assertThat(mismatches()).isEqualTo(1.0);
    }

    @Test
    void a_reading_without_a_ship_id_is_left_to_the_validation_filter() {
        check.check("anything", aReading(null));
        check.check("anything", null);

        assertThat(mismatches()).isZero();
    }

    private double mismatches() {
        return meterRegistry.get(PartitionKeyCheck.METRIC).counter().count();
    }

    private static RawTelemetry aReading(String shipId) {
        return new RawTelemetry(shipId, "TEMPERATURE", 20.0, Instant.parse("2030-01-01T00:00:00Z"), Map.of());
    }
}
