package com.galactic.fleet.routeplanning.internal;

import com.galactic.fleet.routeplanning.api.PlanRouteUseCase;
import com.galactic.fleet.routeplanning.api.PlannedRoute;
import com.galactic.fleet.routeplanning.api.RouteRejectionException;
import com.galactic.fleet.routeplanning.api.RouteRequest;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

/**
 * Plans a route: a pure computation over the corridor and the ship class (ADR-0043). It performs no
 * I/O and publishes nothing — the caller gets the route as the return value and decides what to do
 * with it. That is what makes the synchronous call from {@code booking} acceptable.
 */
@Slf4j
@Service
class PlanRouteService implements PlanRouteUseCase {

    private static final double MIN_FUEL_RANGE_LY = 1.0;
    private static final String REJECTION_INSUFFICIENT_RANGE = "INSUFFICIENT_RANGE";
    private static final double BASE_ETA_SCOUT = 8.0;
    private static final double BASE_ETA_FREIGHTER = 18.0;
    private static final double BASE_ETA_CRUISER = 12.0;
    private static final double BASE_ETA_DEFAULT = 20.0;
    private static final double RISK_ETA_MULTIPLIER = 10.0;
    private static final int RISK_BUCKETS = 10_000;
    private static final String ROUTE_ID_PREFIX = "ROUTE-";
    private static final int ROUTE_ID_SUFFIX_LENGTH = 8;
    private static final String STEP_COMPUTE = "compute";

    private final RouteMetrics metrics;
    private final ShipFuelRanges fuelRanges;

    PlanRouteService(RouteMetrics metrics, ShipFuelRanges fuelRanges) {
        this.metrics = metrics;
        this.fuelRanges = fuelRanges;
    }

    @Override
    public PlannedRoute planRoute(RouteRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        return metrics.observePlan(request, () -> doPlan(request));
    }

    private PlannedRoute doPlan(RouteRequest request) {
        validateFuelRange(request);

        // A named child span (routes.plan.compute), so the routing math stays a visible phase in Tempo
        // when more steps are added to planning.
        PlannedRoute result = metrics.observeStep(STEP_COMPUTE, () -> compute(request));

        // routeId is generated in compute() (not propagated via baggage), so push it into MDC for the
        // OTLP log appender (captureMdcAttributes: routeId).
        MDC.put("routeId", result.routeId());
        try {
            log.info(
                    "Route planned: {} from {} to {} — eta={}h risk={}",
                    result.routeId(),
                    request.originPortId(),
                    request.destinationPortId(),
                    result.etaHours(),
                    result.riskScore());
        } finally {
            MDC.remove("routeId");
        }
        metrics.recordPlanned(request, result);
        return result;
    }

    private PlannedRoute compute(RouteRequest request) {
        double riskScore = computeRiskScore(request);
        double etaHours = computeEta(request, riskScore);
        return PlannedRoute.builder()
                .routeId(generateRouteId())
                .etaHours(etaHours)
                .riskScore(riskScore)
                .plannedAt(Instant.now())
                .build();
    }

    private double computeRiskScore(RouteRequest request) {
        // Deterministic "ion-storm density" of the hyperspace corridor linking the two ports.
        // The same corridor always yields the same risk — reproducible and explainable, unlike the
        // previous dice-roll. Symmetric: A→B and B→A share a corridor, so they share a hazard rating.
        String origin = request.originPortId().trim().toUpperCase();
        String destination = request.destinationPortId().trim().toUpperCase();
        String corridor =
                origin.compareTo(destination) <= 0 ? origin + "::" + destination : destination + "::" + origin;
        // Fold the stable String hash into a uniform value in [0.0, 1.0).
        int bucket = Math.floorMod(corridor.hashCode(), RISK_BUCKETS);
        return (double) bucket / RISK_BUCKETS;
    }

    private String generateRouteId() {
        return ROUTE_ID_PREFIX
                + UUID.randomUUID()
                        .toString()
                        .substring(0, ROUTE_ID_SUFFIX_LENGTH)
                        .toUpperCase();
    }

    private void validateFuelRange(RouteRequest request) {
        double fuelRangeLY = fuelRanges.rangeLY(request.shipClass());
        if (fuelRangeLY < MIN_FUEL_RANGE_LY) {
            metrics.recordRejected(request, REJECTION_INSUFFICIENT_RANGE);
            throw new RouteRejectionException(
                    REJECTION_INSUFFICIENT_RANGE,
                    "Required minimum fuel range is " + MIN_FUEL_RANGE_LY + " LY, but a " + request.shipClass()
                            + " only has " + fuelRangeLY + " LY");
        }
    }

    private double computeEta(RouteRequest request, double riskScore) {
        // Base ETA depends on ship class; higher risk = slightly longer journey
        double baseEta =
                switch (request.shipClass().toUpperCase()) {
                    case "SCOUT" -> BASE_ETA_SCOUT;
                    case "FREIGHTER", "FREIGHTER_MK2" -> BASE_ETA_FREIGHTER;
                    case "CRUISER" -> BASE_ETA_CRUISER;
                    default -> BASE_ETA_DEFAULT;
                };
        return baseEta + (riskScore * RISK_ETA_MULTIPLIER);
    }
}
