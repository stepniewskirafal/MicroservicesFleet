package com.galactic.router.application;

import java.util.concurrent.ThreadLocalRandom;

import com.galactic.router.domain.PlanRouteCommand;
import com.galactic.router.domain.PlannedRoute;
import com.galactic.router.domain.RoutePlanningUseCase;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;

public class StubRoutePlanner implements RoutePlanningUseCase {
    private static final String OBSERVATION_NAME = "routes.plan";
    private static final String METRIC_SUCCESS = "routes.plan.success";
    private static final String METRIC_ERROR = "routes.plan.error";

    private final ObservationRegistry observationRegistry;
    private final Counter successCounter;
    private final Counter errorCounter;

    public StubRoutePlanner(MeterRegistry meterRegistry, ObservationRegistry observationRegistry) {
        this.observationRegistry = observationRegistry;
        this.successCounter = Counter.builder(METRIC_SUCCESS)
                .description("Number of successfully planned routes")
                .register(meterRegistry);
        this.errorCounter = Counter.builder(METRIC_ERROR)
                .description("Number of failed route planning attempts")
                .register(meterRegistry);
    }

    @Override
    public PlannedRoute planRoute(PlanRouteCommand command) {
        return Observation.createNotStarted(OBSERVATION_NAME, observationRegistry)
                .lowCardinalityKeyValue("originPort", command.getOriginPortId())
                .lowCardinalityKeyValue("destinationPort", command.getDestinationPortId())
                .observe(() -> {
                    try {
                        PlannedRoute route = doPlanRoute(command);
                        successCounter.increment();
                        return route;
                    } catch (RuntimeException e) {
                        errorCounter.increment();
                        throw e;
                    }
                });
    }

    private PlannedRoute doPlanRoute(PlanRouteCommand command) {
        double risk = ThreadLocalRandom.current().nextDouble();
        double eta = 12.0 + (risk * 6.0);
        String suffix = String.valueOf(ThreadLocalRandom.current().nextInt(10000, 99999));
        String routeId = String.format("ROUTE-%s-%s-%s", command.getOriginPortId(), command.getDestinationPortId(), suffix);
        return new PlannedRoute(routeId, eta, risk);
    }
}
