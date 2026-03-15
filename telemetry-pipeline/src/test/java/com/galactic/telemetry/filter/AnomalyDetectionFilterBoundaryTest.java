package com.galactic.telemetry.filter;

import static org.assertj.core.api.Assertions.assertThat;

import com.galactic.telemetry.model.AggregatedTelemetry;
import com.galactic.telemetry.model.AnomalyAlert;
import com.galactic.telemetry.model.SensorType;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

/**
 * Boundary-value tests for {@link AnomalyDetectionFilter}.
 * Covers exact-threshold values and edge conditions not covered by the main test.
 */
@Execution(ExecutionMode.CONCURRENT)
class AnomalyDetectionFilterBoundaryTest {

    private AnomalyDetectionFilter filter;

    @BeforeEach
    void setUp() {
        filter = new AnomalyDetectionFilter(new SimpleMeterRegistry());
    }

    @Test
    void should_not_alert_when_value_equals_upper_threshold() {
        // Uses > not >=, so exactly AT the threshold should NOT trigger
        // avg=100, stdDev=0 avoids statistical anomaly path
        AggregatedTelemetry input = aggregated(200.0, 0.0, 200.0, 200.0, 10, 0.0);

        AnomalyAlert result = filter.apply(input);

        assertThat(result).isNull();
    }

    @Test
    void should_not_alert_when_value_equals_lower_threshold() {
        // Uses < not <=, so exactly AT the threshold should NOT trigger
        // avg=50, stdDev=0 avoids statistical anomaly path
        AggregatedTelemetry input = aggregated(50.0, 50.0, 50.0, 200.0, 10, 0.0);

        AnomalyAlert result = filter.apply(input);

        assertThat(result).isNull();
    }

    @Test
    void should_not_alert_when_deviation_equals_exactly_3_sigma() {
        // Uses > not >=, so exactly at 3σ should NOT trigger
        double avg = 100.0;
        double stdDev = 10.0;
        double value = avg + 3.0 * stdDev; // exactly 130.0

        AggregatedTelemetry input = aggregated(value, 0.0, avg, 200.0, 10, stdDev);

        AnomalyAlert result = filter.apply(input);

        assertThat(result).isNull();
    }

    @Test
    void should_alert_when_value_just_above_upper_threshold() {
        AggregatedTelemetry input = aggregated(200.001, 0.0, 100.0, 200.0, 10, 0.0);

        AnomalyAlert result = filter.apply(input);

        assertThat(result).isNotNull();
        assertThat(result.severity()).isEqualTo(AnomalyAlert.Severity.CRITICAL);
    }

    @Test
    void should_alert_when_value_just_below_lower_threshold() {
        AggregatedTelemetry input = aggregated(49.999, 50.0, 100.0, 200.0, 10, 0.0);

        AnomalyAlert result = filter.apply(input);

        assertThat(result).isNotNull();
        assertThat(result.severity()).isEqualTo(AnomalyAlert.Severity.CRITICAL);
    }

    @Test
    void should_set_detected_at_timestamp() {
        AggregatedTelemetry input = aggregated(999.0, 0.0, 100.0, 200.0, 10, 0.0);

        AnomalyAlert result = filter.apply(input);

        assertThat(result.detectedAt()).isNotNull();
    }

    @Test
    void should_include_ship_class_and_sector_in_alert() {
        AggregatedTelemetry input = aggregated(999.0, 0.0, 100.0, 200.0, 10, 0.0);

        AnomalyAlert result = filter.apply(input);

        assertThat(result.shipClass()).isEqualTo("CRUISER");
        assertThat(result.currentSector()).isEqualTo("Sector-7");
    }

    @Test
    void should_prefer_threshold_over_statistical_anomaly() {
        // Value breaches both upper threshold AND is statistically anomalous
        double avg = 100.0;
        double stdDev = 10.0;
        double value = 250.0; // > upperThreshold(200) AND > avg + 3σ(130)

        AggregatedTelemetry input = aggregated(value, 0.0, avg, 200.0, 10, stdDev);

        AnomalyAlert result = filter.apply(input);

        assertThat(result.severity()).isEqualTo(AnomalyAlert.Severity.CRITICAL);
    }

    @Test
    void should_handle_negative_rolling_average() {
        double avg = -50.0;
        double stdDev = 5.0;
        double value = -90.0; // deviation = 40, 3σ = 15

        AggregatedTelemetry input = aggregated(value, -200.0, avg, 200.0, 10, stdDev);

        AnomalyAlert result = filter.apply(input);

        assertThat(result).isNotNull();
        assertThat(result.severity()).isEqualTo(AnomalyAlert.Severity.WARNING);
    }

    private static AggregatedTelemetry aggregated(
            double currentValue, double lowerThreshold, double rollingAvg,
            double upperThreshold, long sampleCount, double stdDev) {
        return new AggregatedTelemetry(
                "SHIP-001", "CRUISER", "Sector-7",
                SensorType.TEMPERATURE, currentValue, rollingAvg,
                currentValue, stdDev, sampleCount,
                Instant.now(), Instant.now(),
                lowerThreshold, upperThreshold);
    }
}
