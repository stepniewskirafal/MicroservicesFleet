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
 * Composes the four stateless filters into a single Spring Cloud Stream function binding.
 *
 * <p>Pipeline: RawTelemetry → validation → enrichment → aggregation → anomaly detection →
 * AnomalyAlert (or null if no anomaly).
 *
 * <p>Spring Cloud Stream automatically subscribes to the input topic and publishes non-null results
 * to the output topic. Null returns are filtered out (no message published).
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
            AnomalyDetectionFilter anomalyDetectionFilter) {

        return raw -> {
            var validated = validationFilter.apply(raw);
            if (validated == null) {
                return null;
            }

            var enriched = enrichmentFilter.apply(validated);
            var aggregated = aggregationFilter.apply(enriched);

            return anomalyDetectionFilter.apply(aggregated);
        };
    }
}
