package com.galactic.traderoute.application;

import com.galactic.traderoute.domain.model.PlannedRoute;
import com.galactic.traderoute.domain.model.RoutePlannedEvent;
import com.galactic.traderoute.domain.model.RouteRejectionException;
import com.galactic.traderoute.domain.model.RouteRequest;
import com.galactic.traderoute.port.in.PlanRouteUseCase;
import com.galactic.traderoute.port.out.RouteEventPublisher;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class PlanRouteService implements PlanRouteUseCase {

    private static final double MIN_FUEL_RANGE_LY = 1.0;
    private static final String OBSERVATION_NAME = "routes.plan";
    private static final String METRIC_SUCCESS = "routes.planned.count";
    private static final String METRIC_REJECTED = "routes.rejected.count";
    private static final String METRIC_RISK_SCORE = "routes.risk.score";
    private static final String METRIC_ETA_HOURS = "routes.eta.hours";
    private static final double BASE_ETA_SCOUT = 8.0;
    private static final double BASE_ETA_FREIGHTER = 18.0;
    private static final double BASE_ETA_CRUISER = 12.0;
    private static final double BASE_ETA_DEFAULT = 20.0;
    private static final double RISK_ETA_MULTIPLIER = 10.0;
    private static final String ROUTE_ID_PREFIX = "ROUTE-";
    private static final int ROUTE_ID_SUFFIX_LENGTH = 8;

    private final ObservationRegistry observationRegistry;
    private final MeterRegistry meterRegistry;
    private final RouteEventPublisher routeEventPublisher;
    private final Counter plannedCounter;
    private final DistributionSummary riskScoreSummary;

    public PlanRouteService(
            MeterRegistry meterRegistry,
            ObservationRegistry observationRegistry,
            RouteEventPublisher routeEventPublisher) {
        this.observationRegistry = observationRegistry;
        this.meterRegistry = meterRegistry;
        this.routeEventPublisher = routeEventPublisher;
        this.plannedCounter = Counter.builder(METRIC_SUCCESS)
                .description("Number of successfully planned routes")
                .register(meterRegistry);
        this.riskScoreSummary = DistributionSummary.builder(METRIC_RISK_SCORE)
                .description("Distribution of route risk scores (0=safe, 1=dangerous)")
                .register(meterRegistry);
    }

    @Override
    public PlannedRoute planRoute(RouteRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        return Observation.createNotStarted(OBSERVATION_NAME, observationRegistry)
                .lowCardinalityKeyValue("originPortId", request.originPortId())
                .lowCardinalityKeyValue("destinationPortId", request.destinationPortId())
                .lowCardinalityKeyValue("shipClass", request.shipClass())
                .observe(() -> doPlan(request));
    }

    private PlannedRoute doPlan(RouteRequest request) {
        validateFuelRange(request);

        double riskScore = ThreadLocalRandom.current().nextDouble(0.0, 1.0);
        double etaHours = computeEta(request, riskScore);
        String routeId = generateRouteId();

        log.info("Route planned: {} from {} to {} — eta={}h risk={}",
                routeId, request.originPortId(), request.destinationPortId(), etaHours, riskScore);

        recordMetrics(request, riskScore, etaHours);

        PlannedRoute result = PlannedRoute.builder()
                .routeId(routeId)
                .etaHours(etaHours)
                .riskScore(riskScore)
                .build();

        publishRouteEvent(request, routeId, etaHours, riskScore);

        return result;
    }

    private String generateRouteId() {
        return ROUTE_ID_PREFIX + UUID.randomUUID().toString().substring(0, ROUTE_ID_SUFFIX_LENGTH).toUpperCase();
    }

    private void recordMetrics(RouteRequest request, double riskScore, double etaHours) {
        riskScoreSummary.record(riskScore);

        DistributionSummary.builder(METRIC_ETA_HOURS)
                .description("Distribution of planned route ETA in hours")
                .baseUnit("hours")
                .tag("shipClass", request.shipClass())
                .register(meterRegistry)
                .record(etaHours);

        plannedCounter.increment();
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
            case "SCOUT" -> BASE_ETA_SCOUT;
            case "FREIGHTER", "FREIGHTER_MK2" -> BASE_ETA_FREIGHTER;
            case "CRUISER" -> BASE_ETA_CRUISER;
            default -> BASE_ETA_DEFAULT;
        };
        return baseEta + (riskScore * RISK_ETA_MULTIPLIER);
    }
}
