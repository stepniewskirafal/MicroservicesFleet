package com.galactic.telemetry.filter;

import static org.assertj.core.api.Assertions.assertThat;

import com.galactic.telemetry.model.AggregatedTelemetry;
import com.galactic.telemetry.model.AnomalyAlert;
import com.galactic.telemetry.model.AnomalyAlert.Severity;
import com.galactic.telemetry.model.SensorType;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AnomalyDetectionFilterTest {

    private AnomalyDetectionFilter filter;
    private SimpleMeterRegistry meterRegistry;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        filter = new AnomalyDetectionFilter(meterRegistry);
    }

    @Test
    void normalValue_returnsNull() {
        AggregatedTelemetry input = aggregated(42.0, 40.0, 45.0, 2.0, 10, -50.0, 300.0);

        assertThat(filter.apply(input)).isNull();
    }

    @Test
    void valueAboveUpperThreshold_returnsCriticalAlert() {
        AggregatedTelemetry input = aggregated(350.0, 40.0, 45.0, 2.0, 10, -50.0, 300.0);

        AnomalyAlert alert = filter.apply(input);

        assertThat(alert).isNotNull();
        assertThat(alert.severity()).isEqualTo(Severity.CRITICAL);
        assertThat(alert.currentValue()).isEqualTo(350.0);
        assertThat(alert.threshold()).isEqualTo(300.0);
        assertThat(alert.description()).contains("exceeded upper threshold");
    }

    @Test
    void valueBelowLowerThreshold_returnsCriticalAlert() {
        AggregatedTelemetry input = aggregated(-100.0, 40.0, 45.0, 2.0, 10, -50.0, 300.0);

        AnomalyAlert alert = filter.apply(input);

        assertThat(alert).isNotNull();
        assertThat(alert.severity()).isEqualTo(Severity.CRITICAL);
        assertThat(alert.description()).contains("below lower threshold");
    }

    @Test
    void statisticalAnomaly_returnsWarningAlert() {
        // value=100, avg=40, stdDev=5 → deviation=60 > 3*5=15 → anomaly
        AggregatedTelemetry input = aggregated(100.0, 40.0, 100.0, 5.0, 10, -50.0, 300.0);

        AnomalyAlert alert = filter.apply(input);

        assertThat(alert).isNotNull();
        assertThat(alert.severity()).isEqualTo(Severity.WARNING);
        assertThat(alert.description()).contains("statistical anomaly");
    }

    @Test
    void statisticalAnomaly_notTriggeredWithFewSamples() {
        // Only 2 samples — not enough for statistical detection
        AggregatedTelemetry input = aggregated(100.0, 40.0, 100.0, 5.0, 2, -50.0, 300.0);

        assertThat(filter.apply(input)).isNull();
    }

    @Test
    void statisticalAnomaly_notTriggeredWithZeroStdDev() {
        AggregatedTelemetry input = aggregated(100.0, 40.0, 100.0, 0.0, 10, -50.0, 300.0);

        assertThat(filter.apply(input)).isNull();
    }

    @Test
    void criticalAlert_incrementsCounter() {
        AggregatedTelemetry input = aggregated(350.0, 40.0, 45.0, 2.0, 10, -50.0, 300.0);

        filter.apply(input);

        assertThat(meterRegistry
                        .counter("telemetry.anomalies.detected", "severity", "CRITICAL")
                        .count())
                .isEqualTo(1.0);
    }

    @Test
    void warningAlert_incrementsCounter() {
        AggregatedTelemetry input = aggregated(100.0, 40.0, 100.0, 5.0, 10, -50.0, 300.0);

        filter.apply(input);

        assertThat(meterRegistry
                        .counter("telemetry.anomalies.detected", "severity", "WARNING")
                        .count())
                .isEqualTo(1.0);
    }

    @Test
    void alertContainsShipAndSectorInfo() {
        AggregatedTelemetry input = aggregated(350.0, 40.0, 45.0, 2.0, 10, -50.0, 300.0);

        AnomalyAlert alert = filter.apply(input);

        assertThat(alert.shipId()).isEqualTo("SHIP-001");
        assertThat(alert.shipClass()).isEqualTo("Corvette");
        assertThat(alert.currentSector()).isEqualTo("Alpha-Centauri");
        assertThat(alert.sensorType()).isEqualTo(SensorType.TEMPERATURE);
    }

    @Test
    void thresholdCheck_takesPriorityOverStatistical() {
        // Value exceeds threshold AND is a statistical anomaly — should be CRITICAL (threshold), not WARNING
        AggregatedTelemetry input = aggregated(350.0, 40.0, 350.0, 5.0, 10, -50.0, 300.0);

        AnomalyAlert alert = filter.apply(input);

        assertThat(alert.severity()).isEqualTo(Severity.CRITICAL);
    }

    private AggregatedTelemetry aggregated(
            double currentValue,
            double rollingAvg,
            double rollingMax,
            double rollingStdDev,
            long sampleCount,
            double lowerThreshold,
            double upperThreshold) {
        return new AggregatedTelemetry(
                "SHIP-001",
                "Corvette",
                "Alpha-Centauri",
                SensorType.TEMPERATURE,
                currentValue,
                rollingAvg,
                rollingMax,
                rollingStdDev,
                sampleCount,
                Instant.now().minusSeconds(300),
                Instant.now(),
                lowerThreshold,
                upperThreshold);
    }
}
