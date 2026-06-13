package com.galactic.traderoute.adapter.out.metrics;

import com.galactic.traderoute.domain.model.PlannedRoute;
import com.galactic.traderoute.domain.model.RouteRequest;
import com.galactic.traderoute.port.out.RouteMetricsPort;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/**
 * Micrometer-backed implementation of {@link RouteMetricsPort}. All observability wiring lives here,
 * in the adapter layer — the application core has no Micrometer dependency.
 */
@Component
public class MicrometerRouteMetricsAdapter implements RouteMetricsPort {

    private static final String OBSERVATION_NAME = "routes.plan";
    private static final String METRIC_SUCCESS = "routes.planned.count";
    private static final String METRIC_REJECTED = "routes.rejected.count";
    private static final String METRIC_PUBLISH_FAILED = "routes.publish.failed";
    private static final String METRIC_RISK_SCORE = "routes.risk.score";
    private static final String METRIC_ETA_HOURS = "routes.eta.hours";

    private final MeterRegistry meterRegistry;
    private final ObservationRegistry observationRegistry;
    private final Counter plannedCounter;
    private final DistributionSummary riskScoreSummary;

    public MicrometerRouteMetricsAdapter(MeterRegistry meterRegistry, ObservationRegistry observationRegistry) {
        this.meterRegistry = meterRegistry;
        this.observationRegistry = observationRegistry;
        this.plannedCounter = Counter.builder(METRIC_SUCCESS)
                .description("Number of successfully planned routes")
                .register(meterRegistry);
        this.riskScoreSummary = DistributionSummary.builder(METRIC_RISK_SCORE)
                .description("Distribution of route risk scores (0=safe, 1=dangerous)")
                .register(meterRegistry);
    }

    @Override
    public PlannedRoute observePlan(RouteRequest request, Supplier<PlannedRoute> planning) {
        // Port IDs are unbounded — keep them on the span (searchable in Tempo) but OFF the
        // routes.plan timer, otherwise every origin×destination pair spawns a new Prometheus series
        // (ADR-0030). shipClass is a small closed enum, so it stays low-cardinality.
        return Observation.createNotStarted(OBSERVATION_NAME, observationRegistry)
                .highCardinalityKeyValue("originPortId", request.originPortId())
                .highCardinalityKeyValue("destinationPortId", request.destinationPortId())
                .lowCardinalityKeyValue("shipClass", request.shipClass())
                .observe(planning);
    }

    @Override
    public void recordPlanned(RouteRequest request, PlannedRoute route) {
        riskScoreSummary.record(route.riskScore());

        DistributionSummary.builder(METRIC_ETA_HOURS)
                .description("Distribution of planned route ETA in hours")
                .baseUnit("hours")
                .tag("shipClass", request.shipClass())
                .register(meterRegistry)
                .record(route.etaHours());

        plannedCounter.increment();
    }

    @Override
    public void recordRejected(RouteRequest request, String reason) {
        Counter.builder(METRIC_REJECTED)
                .description("Number of rejected route planning attempts")
                .tag("reason", reason)
                .register(meterRegistry)
                .increment();
    }

    @Override
    public void recordPublishFailed(RouteRequest request) {
        Counter.builder(METRIC_PUBLISH_FAILED)
                .description("Routes computed but whose RoutePlannedEvent failed to publish")
                .tag("shipClass", request.shipClass())
                .register(meterRegistry)
                .increment();
    }
}
