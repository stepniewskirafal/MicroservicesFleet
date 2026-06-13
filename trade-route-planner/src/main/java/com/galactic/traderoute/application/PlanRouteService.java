package com.galactic.traderoute.application;

import com.galactic.traderoute.domain.model.PlannedRoute;
import com.galactic.traderoute.domain.model.RoutePlannedEvent;
import com.galactic.traderoute.domain.model.RouteRejectionException;
import com.galactic.traderoute.domain.model.RouteRequest;
import com.galactic.traderoute.port.in.PlanRouteUseCase;
import com.galactic.traderoute.port.out.RouteEventPublisher;
import com.galactic.traderoute.port.out.RouteMetricsPort;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;

@Slf4j
public class PlanRouteService implements PlanRouteUseCase {

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

    private final RouteMetricsPort metrics;
    private final RouteEventPublisher routeEventPublisher;

    public PlanRouteService(RouteMetricsPort metrics, RouteEventPublisher routeEventPublisher) {
        this.metrics = metrics;
        this.routeEventPublisher = routeEventPublisher;
    }

    @Override
    public PlannedRoute planRoute(RouteRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        return metrics.observePlan(request, () -> doPlan(request));
    }

    private PlannedRoute doPlan(RouteRequest request) {
        validateFuelRange(request);

        double riskScore = computeRiskScore(request);
        double etaHours = computeEta(request, riskScore);
        String routeId = generateRouteId();

        // routeId is generated here (not propagated via baggage), so push it into MDC for the OTLP log
        // appender (captureMdcAttributes: routeId) — covers this log line and the publisher's.
        MDC.put("routeId", routeId);
        try {
            log.info(
                    "Route planned: {} from {} to {} — eta={}h risk={}",
                    routeId,
                    request.originPortId(),
                    request.destinationPortId(),
                    etaHours,
                    riskScore);

            PlannedRoute result = PlannedRoute.builder()
                    .routeId(routeId)
                    .etaHours(etaHours)
                    .riskScore(riskScore)
                    .build();

            // Publish the event BEFORE recording success metrics: a route only counts as "planned"
            // once its RoutePlannedEvent is on the wire. Recording metrics first over-counts
            // routes.planned.count (and the ETA/risk summaries) whenever publishing fails. On failure
            // we record routes.publish.failed and propagate (no outbox here yet — the event is lost),
            // so recordPlanned() never runs and the success counter is not inflated.
            try {
                publishRouteEvent(request, routeId, etaHours, riskScore);
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

    private double computeRiskScore(RouteRequest request) {
        // Deterministic "ion-storm density" of the hyperspace corridor linking the two ports.
        // The same corridor always yields the same risk — reproducible and explainable, unlike the
        // previous dice-roll. Symmetric: A→B and B→A share a corridor, so they share a hazard rating.
        String origin = request.originPortId().trim().toUpperCase();
        String destination = request.destinationPortId().trim().toUpperCase();
        String corridor = origin.compareTo(destination) <= 0
                ? origin + "::" + destination
                : destination + "::" + origin;
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

    private void publishRouteEvent(RouteRequest request, String routeId, double etaHours, double riskScore) {
        routeEventPublisher.publish(RoutePlannedEvent.builder()
                .routeId(routeId)
                .originPortId(request.originPortId())
                .destinationPortId(request.destinationPortId())
                .shipClass(request.shipClass())
                .etaHours(etaHours)
                .riskScore(riskScore)
                .plannedAt(Instant.now())
                .build());
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
