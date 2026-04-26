package com.galactic.telemetry.pipeline;

import com.galactic.telemetry.config.SensorThresholdProperties;
import com.galactic.telemetry.filter.AggregationFilter;
import com.galactic.telemetry.filter.AnomalyDetectionFilter;
import com.galactic.telemetry.filter.EnrichmentFilter;
import com.galactic.telemetry.filter.ValidationFilter;
import com.galactic.telemetry.model.AnomalyAlert;
import com.galactic.telemetry.model.RawTelemetry;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
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

    private static final String OBS_PIPELINE_PROCESS = "telemetry.pipeline.process";
    private static final String METRIC_REQUESTS_TOTAL = "telemetry.pipeline.requests.total";
    private static final String METRIC_REQUEST_ERRORS_TOTAL = "telemetry.pipeline.request.errors.total";

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
            MeterRegistry meterRegistry,
            ObservationRegistry observationRegistry) {

        Counter requestsCounter = Counter.builder(METRIC_REQUESTS_TOTAL)
                .description("Total telemetry pipeline invocations (RED Rate)")
                .tag("pipeline", "telemetry")
                .register(meterRegistry);

        return raw -> Observation.createNotStarted(OBS_PIPELINE_PROCESS, observationRegistry)
                .lowCardinalityKeyValue("pipeline", "telemetry")
                .observe(() -> {
                    requestsCounter.increment();
                    try {
                        var validated = validationFilter.apply(raw);
                        if (validated == null) {
                            recordError(meterRegistry, "telemetry", "validation");
                            return null;
                        }

                        var enriched = enrichmentFilter.apply(validated);
                        var aggregated = aggregationFilter.apply(enriched);

                        return anomalyDetectionFilter.apply(aggregated);
                    } catch (RuntimeException ex) {
                        recordError(meterRegistry, "telemetry", "exception");
                        throw ex;
                    }
                });
    }

    static void recordError(MeterRegistry meterRegistry, String pipeline, String stage) {
        Counter.builder(METRIC_REQUEST_ERRORS_TOTAL)
                .description("Telemetry pipeline errors by stage (RED Errors)")
                .tag("pipeline", pipeline)
                .tag("stage", stage)
                .register(meterRegistry)
                .increment();
    }
}
