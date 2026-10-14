package com.galactic.traderoute.planning.internal;

import com.galactic.traderoute.planning.PlanRouteUseCase;
import com.galactic.traderoute.planning.PlannedRoute;
import com.galactic.traderoute.planning.RouteEventPublisher;
import com.galactic.traderoute.planning.RoutePlannedEvent;
import com.galactic.traderoute.planning.RouteRejectionException;
import com.galactic.traderoute.planning.RouteRequest;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

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
    private static final String STEP_PUBLISH = "publish";

    private static final String REJECTION_HAZARD_ZONE = "HAZARD_ZONE";

    private final RouteMetrics metrics;
    private final RouteEventPublisher routeEventPublisher;

    /**
     * Optional destination port that is treated as an interdicted hazard zone: any route INTO it is
     * rejected in the planner (422 ROUTE_REJECTED). Blank/null = disabled. Used to deterministically
     * surface a planner-side error in the trace (load testing / observability demos), instead of every
     * failure being a starport-side validation error before the planner is even called.
     */
    private final String hazardDestinationPortId;

    PlanRouteService(RouteMetrics metrics, RouteEventPublisher routeEventPublisher) {
        this(metrics, routeEventPublisher, null);
    }

    @Autowired
    PlanRouteService(
            RouteMetrics metrics,
            RouteEventPublisher routeEventPublisher,
            @Value("${traderoute.hazard.destination-port:}") String hazardDestinationPortId) {
        this.metrics = metrics;
        this.routeEventPublisher = routeEventPublisher;
        this.hazardDestinationPortId = hazardDestinationPortId;
    }

    @Override
    public PlannedRoute planRoute(RouteRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        return metrics.observePlan(request, () -> doPlan(request));
    }

    private PlannedRoute doPlan(RouteRequest request) {
        rejectIfHazardZone(request);
        validateFuelRange(request);

        // Split the plan into named child spans (routes.plan.compute / routes.plan.publish) so a slow
        // plan is attributable to a phase in Tempo — CPU-bound routing math vs. the Kafka publish hop —
        // instead of collapsing into one opaque routes.plan span.
        PlannedRoute result = metrics.observeStep(STEP_COMPUTE, () -> compute(request));

        // routeId is generated in compute() (not propagated via baggage), so push it into MDC for the
        // OTLP log appender (captureMdcAttributes: routeId) — covers this log line and the publisher's.
        MDC.put("routeId", result.routeId());
        try {
            log.info(
                    "Route planned: {} from {} to {} — eta={}h risk={}",
                    result.routeId(),
                    request.originPortId(),
                    request.destinationPortId(),
                    result.etaHours(),
                    result.riskScore());

            // Publish the event BEFORE recording success metrics: a route only counts as "planned"
            // once its RoutePlannedEvent is on the wire. Recording metrics first over-counts
            // routes.planned.count (and the ETA/risk summaries) whenever publishing fails. On failure
            // we record routes.publish.failed and propagate (no outbox here yet — the event is lost),
            // so recordPlanned() never runs and the success counter is not inflated.
            try {
                metrics.observeStep(STEP_PUBLISH, () -> publishRouteEvent(request, result));
            } catch (RuntimeException ex) {
                metrics.recordPublishFailed(request);
                throw ex;
            }
            metrics.recordPlanned(request, result);

            return result;
        } finally {
            MDC.remove("routeId");
        }
    }

    private PlannedRoute compute(RouteRequest request) {
        double riskScore = computeRiskScore(request);
        double etaHours = computeEta(request, riskScore);
        String routeId = generateRouteId();
        return PlannedRoute.builder()
                .routeId(routeId)
                .etaHours(etaHours)
                .riskScore(riskScore)
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

    private void publishRouteEvent(RouteRequest request, PlannedRoute route) {
        routeEventPublisher.publish(RoutePlannedEvent.builder()
                .routeId(route.routeId())
                .originPortId(request.originPortId())
                .destinationPortId(request.destinationPortId())
                .shipClass(request.shipClass())
                .etaHours(route.etaHours())
                .riskScore(route.riskScore())
                .plannedAt(Instant.now())
                .build());
    }

    private void rejectIfHazardZone(RouteRequest request) {
        if (hazardDestinationPortId == null || hazardDestinationPortId.isBlank()) {
            return;
        }
        if (hazardDestinationPortId.equalsIgnoreCase(request.destinationPortId())) {
            // Reject BEFORE compute/publish: this trace's routes.plan span (and the server span) carry
            // the error, so Tempo shows the failure originated HERE in the planner — not in starport.
            metrics.recordRejected(request, REJECTION_HAZARD_ZONE);
            throw new RouteRejectionException(
                    REJECTION_HAZARD_ZONE,
                    "Route into interdicted hazard zone '" + request.destinationPortId() + "' is not permitted");
        }
    }

    private void validateFuelRange(RouteRequest request) {
        if (request.fuelRangeLY() < MIN_FUEL_RANGE_LY) {
            metrics.recordRejected(request, REJECTION_INSUFFICIENT_RANGE);
            throw new RouteRejectionException(
                    REJECTION_INSUFFICIENT_RANGE,
                    "Required minimum fuel range is " + MIN_FUEL_RANGE_LY + " LY, but ship only has "
                            + request.fuelRangeLY() + " LY");
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
