package com.galactic.telemetry.filter;

import com.galactic.telemetry.model.AggregatedTelemetry;
import com.galactic.telemetry.model.AnomalyAlert;
import com.galactic.telemetry.model.AnomalyAlert.Severity;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AnomalyDetectionFilter implements Function<AggregatedTelemetry, AnomalyAlert> {

    private static final Logger log = LoggerFactory.getLogger(AnomalyDetectionFilter.class);
    private static final double STATISTICAL_SIGMA = 3.0;

    private final Counter warningCounter;
    private final Counter criticalCounter;

    public AnomalyDetectionFilter(MeterRegistry meterRegistry) {
        this.warningCounter = Counter.builder("telemetry.anomalies.detected")
                .tag("severity", "WARNING")
                .description("Warning-level anomalies detected")
                .register(meterRegistry);
        this.criticalCounter = Counter.builder("telemetry.anomalies.detected")
                .tag("severity", "CRITICAL")
                .description("Critical-level anomalies detected")
                .register(meterRegistry);
    }

    @Override
    public AnomalyAlert apply(AggregatedTelemetry aggregated) {
        // CRITICAL: value exceeds hard threshold
        if (aggregated.currentValue() > aggregated.upperThreshold()) {
            criticalCounter.increment();
            String description = String.format(
                    "Sensor %s on ship %s exceeded upper threshold: %.2f > %.2f",
                    aggregated.sensorType(),
                    aggregated.shipId(),
                    aggregated.currentValue(),
                    aggregated.upperThreshold());
            log.warn(description);
            return new AnomalyAlert(
                    aggregated.shipId(),
                    aggregated.sensorType(),
                    Severity.CRITICAL,
                    description,
                    aggregated.currentValue(),
                    aggregated.upperThreshold(),
                    aggregated.rollingAvg(),
                    aggregated.shipClass(),
                    aggregated.currentSector(),
                    Instant.now());
        }

        if (aggregated.currentValue() < aggregated.lowerThreshold()) {
            criticalCounter.increment();
            String description = String.format(
                    "Sensor %s on ship %s below lower threshold: %.2f < %.2f",
                    aggregated.sensorType(),
                    aggregated.shipId(),
                    aggregated.currentValue(),
                    aggregated.lowerThreshold());
            log.warn(description);
            return new AnomalyAlert(
                    aggregated.shipId(),
                    aggregated.sensorType(),
                    Severity.CRITICAL,
                    description,
                    aggregated.currentValue(),
                    aggregated.lowerThreshold(),
                    aggregated.rollingAvg(),
                    aggregated.shipClass(),
                    aggregated.currentSector(),
                    Instant.now());
        }

        // WARNING: statistical deviation — value deviates more than 3σ from rolling average
        if (aggregated.windowSampleCount() > 2 && aggregated.rollingStdDev() > 0) {
            double deviation = Math.abs(aggregated.currentValue() - aggregated.rollingAvg());
            if (deviation > STATISTICAL_SIGMA * aggregated.rollingStdDev()) {
                warningCounter.increment();
                String description = String.format(
                        "Sensor %s on ship %s: statistical anomaly — value %.2f deviates %.1fσ from avg %.2f",
                        aggregated.sensorType(),
                        aggregated.shipId(),
                        aggregated.currentValue(),
                        deviation / aggregated.rollingStdDev(),
                        aggregated.rollingAvg());
                log.warn(description);
                return new AnomalyAlert(
                        aggregated.shipId(),
                        aggregated.sensorType(),
                        Severity.WARNING,
                        description,
                        aggregated.currentValue(),
                        aggregated.rollingAvg() + STATISTICAL_SIGMA * aggregated.rollingStdDev(),
                        aggregated.rollingAvg(),
                        aggregated.shipClass(),
                        aggregated.currentSector(),
                        Instant.now());
            }
        }

        // No anomaly — return null (Spring Cloud Stream filters out nulls)
        return null;
    }
}
