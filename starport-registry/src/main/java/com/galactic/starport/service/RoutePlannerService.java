package com.galactic.starport.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@Slf4j
class RoutePlannerService {

    private static final String OBS_ROUTE_PLAN = "reservations.route.plan";
    private static final String OBS_ROUTE_RISK = "reservations.route.risk.calculate";
    private static final String OBS_ROUTE_ETA = "reservations.route.eta.calculate";

    private static final String METRIC_ROUTE_PLAN_SUCCESS = "reservations.route.plan.success";
    private static final String METRIC_ROUTE_PLAN_ERROR = "reservations.route.plan.errors";

    private final ObservationRegistry observationRegistry;
    private final Counter routePlanSuccessCounter;
    private final Counter routePlanErrorCounter;

    RoutePlannerService(MeterRegistry meterRegistry, ObservationRegistry observationRegistry) {
        this.observationRegistry = observationRegistry;

        this.routePlanSuccessCounter = Counter.builder(METRIC_ROUTE_PLAN_SUCCESS)
                .description("Number of successfully planned routes")
                .register(meterRegistry);

        this.routePlanErrorCounter = Counter.builder(METRIC_ROUTE_PLAN_ERROR)
                .description("Number of failed route planning attempts")
                .register(meterRegistry);
    }

    Optional<Route> calculateRoute(ReserveBayCommand command) {
        if (!command.requestRoute()) {
            return Optional.empty();
        }
        return Optional.of(calculate(command));
    }

    private Route calculate(ReserveBayCommand command) {
        return Observation.createNotStarted(OBS_ROUTE_PLAN, observationRegistry)
                .lowCardinalityKeyValue("startStarport", command.startStarportCode())
                .lowCardinalityKeyValue("destinationStarport", command.destinationStarportCode())
                .observe(() -> {
                    try {
                        Route route = doPlanRoute(command);
                        routePlanSuccessCounter.increment();
                        return route;
                    } catch (RuntimeException e) {
                        routePlanErrorCounter.increment();
                        log.error(
                                "Route planning failed for reservation from {} to {}",
                                command.startStarportCode(),
                                command.destinationStarportCode(),
                                e);
                        throw e;
                    }
                });
    }

    private Route doPlanRoute(ReserveBayCommand command) {
        double riskScore = calculateRiskScore(command);
        double etaLightYears = calculateEtaLightYears(command, riskScore);

        return Route.builder()
                .routeCode(getRouteCode(command))
                .startStarportCode(command.startStarportCode())
                .destinationStarportCode(command.destinationStarportCode())
                .etaLightYears(etaLightYears)
                .riskScore(riskScore)
                .isActive(true)
                .build();
    }

    private double calculateRiskScore(ReserveBayCommand command) {
        return Observation.createNotStarted(OBS_ROUTE_RISK, observationRegistry)
                .lowCardinalityKeyValue("startStarport", command.startStarportCode())
                .lowCardinalityKeyValue("destinationStarport", command.destinationStarportCode())
                .observe(() -> {
                    // Future: external risk scoring service call
                    return ThreadLocalRandom.current().nextDouble();
                });
    }

    private double calculateEtaLightYears(ReserveBayCommand command, double riskScore) {
        return Observation.createNotStarted(OBS_ROUTE_ETA, observationRegistry)
                .lowCardinalityKeyValue("startStarport", command.startStarportCode())
                .lowCardinalityKeyValue("destinationStarport", command.destinationStarportCode())
                .observe(() -> {
                    // Future: external ETA service call
                    return 1.0 + riskScore / 100.0;
                });
    }

    private static String getRouteCode(ReserveBayCommand command) {
        return "ROUTE-" + command.startStarportCode() + "-" + command.destinationStarportCode() + "-"
                + ThreadLocalRandom.current().nextInt(100000, 999999);
    }
}

    /*    private void releaseHold(Reservation newReservation) {
        reservationRepository.findById(newReservation.getId()).ifPresent(entity -> {
            entity.cancelRevervation();
            reservationRepository.save(entity);
        });
    }*/
