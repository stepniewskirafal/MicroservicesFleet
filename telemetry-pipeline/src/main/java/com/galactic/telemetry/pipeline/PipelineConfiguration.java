package com.galactic.telemetry.pipeline;

import com.galactic.telemetry.config.SensorThresholdProperties;
import com.galactic.telemetry.filter.AggregationFilter;
import com.galactic.telemetry.filter.AnomalyDetectionFilter;
import com.galactic.telemetry.filter.EnrichmentFilter;
import com.galactic.telemetry.filter.ValidationFilter;
import com.galactic.telemetry.model.AnomalyAlert;
import com.galactic.telemetry.model.RawTelemetry;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.function.Function;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Composes the telemetry filters into a single Spring Cloud Stream function binding.
 *
 * <p>Pipeline: RawTelemetry → validation → enrichment → aggregation → anomaly detection →
 * AnomalyAlert (or null if no anomaly). Three of the filters are stateless; {@code
 * AggregationFilter} is stateful (it keeps per-(ship,sensor) running windows).
 *
 * <p>Composition, per-stage timing and error isolation are handled uniformly by {@link
 * PipelineBuilder} — adding a filter is one {@code .stage(...)} call, no new {@code Timer} or
 * try/catch. Spring Cloud Stream subscribes to the input topic and publishes non-null results to
 * the output topic; null returns are filtered out (no message published).
 */
@Configuration
public class PipelineConfiguration {

    @Bean
    ValidationFilter validationFilter(MeterRegistry meterRegistry) {
        return new ValidationFilter(meterRegistry);
    }

    @Bean
    EnrichmentFilter enrichmentFilter(SensorThresholdProperties thresholdProperties) {
        return new EnrichmentFilter(thresholdProperties);
    }

    @Bean
    AggregationFilter aggregationFilter() {
        return new AggregationFilter();
    }

    @Bean
    AnomalyDetectionFilter anomalyDetectionFilter(MeterRegistry meterRegistry) {
        return new AnomalyDetectionFilter(meterRegistry);
    }

    @Bean
    public Function<RawTelemetry, AnomalyAlert> telemetryPipeline(
            ValidationFilter validationFilter,
            EnrichmentFilter enrichmentFilter,
            AggregationFilter aggregationFilter,
            AnomalyDetectionFilter anomalyDetectionFilter,
            MeterRegistry meterRegistry) {

        return PipelineBuilder.<RawTelemetry>create(meterRegistry)
                .stage("validation", validationFilter)
                .stage("enrichment", enrichmentFilter)
                .stage("aggregation", aggregationFilter)
                .stage("anomaly", anomalyDetectionFilter)
                .build();
    }
}
