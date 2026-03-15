package com.galactic.telemetry.filter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.galactic.telemetry.model.AggregatedTelemetry;
import com.galactic.telemetry.model.EnrichedTelemetry;
import com.galactic.telemetry.model.SensorType;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

@Execution(ExecutionMode.CONCURRENT)
class AggregationFilterEdgeCaseTest {

    private AggregationFilter filter;

    @BeforeEach
    void setUp() {
        filter = new AggregationFilter();
        filter.clearWindows();
    }

    @Test
    void should_reset_window_when_expired() {
        Instant t0 = Instant.parse("2026-01-01T00:00:00Z");
        Instant t1 = Instant.parse("2026-01-01T00:01:00Z");
        // 6 minutes later — beyond the 5-minute window
        Instant t2 = Instant.parse("2026-01-01T00:06:01Z");

        filter.apply(enriched("SHIP-1", SensorType.TEMPERATURE, 100.0, t0));
        filter.apply(enriched("SHIP-1", SensorType.TEMPERATURE, 200.0, t1));

        // Window should reset after 5+ minutes
        AggregatedTelemetry result = filter.apply(enriched("SHIP-1", SensorType.TEMPERATURE, 50.0, t2));

        assertThat(result.windowSampleCount()).isEqualTo(1);
        assertThat(result.rollingAvg()).isEqualTo(50.0);
        assertThat(result.rollingStdDev()).isEqualTo(0.0);
    }

    @Test
    void should_compute_correct_stddev_for_known_values() {
        Instant t = Instant.parse("2026-01-01T00:00:00Z");

        filter.apply(enriched("SHIP-1", SensorType.TEMPERATURE, 2.0, t));
        filter.apply(enriched("SHIP-1", SensorType.TEMPERATURE, 4.0, t.plusSeconds(1)));
        filter.apply(enriched("SHIP-1", SensorType.TEMPERATURE, 4.0, t.plusSeconds(2)));
        AggregatedTelemetry result = filter.apply(
                enriched("SHIP-1", SensorType.TEMPERATURE, 4.0, t.plusSeconds(3)));

        // Values: [2, 4, 4, 4], mean = 3.5
        // Sample stddev = sqrt(((2-3.5)^2 + 3*(4-3.5)^2) / 3) = sqrt((2.25 + 0.75)/3) = sqrt(1.0) = 1.0
        assertThat(result.rollingAvg()).isCloseTo(3.5, within(0.01));
        assertThat(result.rollingStdDev()).isCloseTo(1.0, within(0.01));
    }

    @Test
    void should_track_max_correctly_with_decreasing_values() {
        Instant t = Instant.parse("2026-01-01T00:00:00Z");

        filter.apply(enriched("SHIP-1", SensorType.FUEL_LEVEL, 100.0, t));
        filter.apply(enriched("SHIP-1", SensorType.FUEL_LEVEL, 80.0, t.plusSeconds(1)));
        AggregatedTelemetry result = filter.apply(
                enriched("SHIP-1", SensorType.FUEL_LEVEL, 60.0, t.plusSeconds(2)));

        assertThat(result.rollingMax()).isEqualTo(100.0);
    }

    @Test
    void should_handle_negative_values() {
        Instant t = Instant.parse("2026-01-01T00:00:00Z");

        filter.apply(enriched("SHIP-1", SensorType.TEMPERATURE, -50.0, t));
        AggregatedTelemetry result = filter.apply(
                enriched("SHIP-1", SensorType.TEMPERATURE, -30.0, t.plusSeconds(1)));

        assertThat(result.rollingAvg()).isCloseTo(-40.0, within(0.01));
        assertThat(result.rollingMax()).isEqualTo(-30.0);
    }

    @Test
    void should_not_expire_at_exactly_five_minutes() {
        Instant t0 = Instant.parse("2026-01-01T00:00:00Z");
        // Exactly 5 minutes — compareTo > 0 means strictly greater
        Instant t1 = Instant.parse("2026-01-01T00:05:00Z");

        filter.apply(enriched("SHIP-1", SensorType.TEMPERATURE, 10.0, t0));
        AggregatedTelemetry result = filter.apply(
                enriched("SHIP-1", SensorType.TEMPERATURE, 20.0, t1));

        // Window should NOT have expired at exactly 5 minutes
        assertThat(result.windowSampleCount()).isEqualTo(2);
    }

    @Test
    void two_samples_should_produce_nonzero_stddev_when_values_differ() {
        Instant t = Instant.parse("2026-01-01T00:00:00Z");

        filter.apply(enriched("SHIP-1", SensorType.RADIATION, 10.0, t));
        AggregatedTelemetry result = filter.apply(
                enriched("SHIP-1", SensorType.RADIATION, 20.0, t.plusSeconds(1)));

        assertThat(result.windowSampleCount()).isEqualTo(2);
        assertThat(result.rollingStdDev()).isGreaterThan(0.0);
        // Sample stddev of [10, 20] = sqrt(50) ≈ 7.07
        assertThat(result.rollingStdDev()).isCloseTo(7.07, within(0.1));
    }

    private static EnrichedTelemetry enriched(String shipId, SensorType type, double value, Instant ts) {
        return new EnrichedTelemetry(shipId, "CRUISER", "Sector-7", type, value, ts, -1000.0, 1000.0, Map.of());
    }
}
