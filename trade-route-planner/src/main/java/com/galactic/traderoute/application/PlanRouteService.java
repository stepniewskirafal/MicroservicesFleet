package com.galactic.traderoute.application;

import com.galactic.traderoute.domain.model.PlannedRoute;
import com.galactic.traderoute.domain.model.RouteRejectionException;
import com.galactic.traderoute.domain.model.RouteRequest;
import com.galactic.traderoute.port.in.PlanRouteUseCase;
import io.micrometer.core.instrument.Counter;
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

    private final ObservationRegistry observationRegistry;
    private final Counter plannedCounter;
    private final Counter rejectedCounter;

    PlanRouteService(MeterRegistry meterRegistry, ObservationRegistry observationRegistry) {
        this.observationRegistry = observationRegistry;
        this.plannedCounter = Counter.builder(METRIC_SUCCESS)
                .description("Number of successfully planned routes")
                .register(meterRegistry);
        this.rejectedCounter = Counter.builder(METRIC_REJECTED)
                .description("Number of rejected route planning attempts")
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

        plannedCounter.increment();
        return PlannedRoute.builder()
                .routeId(routeId)
                .etaHours(etaHours)
                .riskScore(riskScore)
                .build();
    }

    private void validateFuelRange(RouteRequest request) {
        if (request.fuelRangeLY() < MIN_FUEL_RANGE_LY) {
            rejectedCounter.increment();
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
