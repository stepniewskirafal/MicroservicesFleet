package com.galactic.traderoute.application;

import com.galactic.traderoute.domain.model.PlannedRoute;
import com.galactic.traderoute.domain.model.RouteRejectionException;
import com.galactic.traderoute.domain.model.RouteRequest;
import com.galactic.traderoute.port.in.PlanRouteUseCase;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@Slf4j
class PlanRouteService implements PlanRouteUseCase {

    private static final double MIN_FUEL_RANGE_LY = 1.0;
    private static final String OBSERVATION_NAME = "routes.plan";
    private static final String METRIC_SUCCESS = "routes.planned.count";
    private static final String METRIC_REJECTED = "routes.rejected.count";
    private static final String METRIC_RISK_SCORE = "routes.risk.score";
    private static final String METRIC_ETA_HOURS = "routes.eta.hours";

    private final ObservationRegistry observationRegistry;
    private final MeterRegistry meterRegistry;
    private final Counter plannedCounter;

    PlanRouteService(MeterRegistry meterRegistry, ObservationRegistry observationRegistry) {
        this.observationRegistry = observationRegistry;
        this.meterRegistry = meterRegistry;
        this.plannedCounter = Counter.builder(METRIC_SUCCESS)
                .description("Number of successfully planned routes")
                .register(meterRegistry);
    }

    @Override
    public PlannedRoute planRoute(RouteRequest request) {
        return Observation.createNotStarted(OBSERVATION_NAME, observationRegistry)
                .lowCardinalityKeyValue("originPortId", request.originPortId())
                .lowCardinalityKeyValue("destinationPortId", request.destinationPortId())
                .lowCardinalityKeyValue("shipClass", request.shipClass())
                .observe(() -> doplan(request));
    }

    private PlannedRoute doplan(RouteRequest request) {
        validateFuelRange(request);

        double riskScore = ThreadLocalRandom.current().nextDouble(0.0, 1.0);
        double etaHours = computeEta(request, riskScore);
        String routeId = "ROUTE-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();

        log.info(
                "Route planned: {} from {} to {} — eta={}h risk={}",
                routeId,
                request.originPortId(),
                request.destinationPortId(),
                etaHours,
                riskScore);

        // Risk score distribution — key input to tariff calculation (up to 20% discount).
        // Micrometer caches meters by (name+tags), so registration is idempotent.
        DistributionSummary.builder(METRIC_RISK_SCORE)
                .description("Distribution of route risk scores (0=safe, 1=dangerous); affects tariff discount")
                .tag("originPortId", request.originPortId())
                .tag("destinationPortId", request.destinationPortId())
                .register(meterRegistry)
                .record(riskScore);

        // ETA distribution — useful for capacity planning and SLA monitoring per ship class.
        DistributionSummary.builder(METRIC_ETA_HOURS)
                .description("Distribution of planned route ETA in hours")
                .baseUnit("hours")
                .tag("shipClass", request.shipClass())
                .register(meterRegistry)
                .record(etaHours);

        plannedCounter.increment();
        return PlannedRoute.builder()
                .routeId(routeId)
                .etaHours(etaHours)
                .riskScore(riskScore)
                .build();
    }

    private void validateFuelRange(RouteRequest request) {
        if (request.fuelRangeLY() < MIN_FUEL_RANGE_LY) {
            // Tag with rejection reason to enable filtering by cause in dashboards.
            Counter.builder(METRIC_REJECTED)
                    .description("Number of rejected route planning attempts")
                    .tag("reason", "INSUFFICIENT_RANGE")
                    .register(meterRegistry)
                    .increment();
            throw new RouteRejectionException(
                    "INSUFFICIENT_RANGE",
                    "Required minimum fuel range is " + MIN_FUEL_RANGE_LY
                            + " LY, but ship only has " + request.fuelRangeLY() + " LY");
        }
    }

    private double computeEta(RouteRequest request, double riskScore) {
        // Base ETA depends on ship class; higher risk = slightly longer journey
        double baseEta = switch (request.shipClass().toUpperCase()) {
            case "SCOUT" -> 8.0;
            case "FREIGHTER", "FREIGHTER_MK2" -> 18.0;
            case "CRUISER" -> 12.0;
            default -> 20.0;
        };
        return baseEta + (riskScore * 10.0);
    }
}
