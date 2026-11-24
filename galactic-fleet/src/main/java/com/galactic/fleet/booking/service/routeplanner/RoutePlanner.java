package com.galactic.fleet.booking.service.routeplanner;

import com.galactic.fleet.booking.service.ReserveBayCommand;
import com.galactic.fleet.booking.service.Route;
import com.galactic.fleet.routeplanning.api.PlanRouteUseCase;
import com.galactic.fleet.routeplanning.api.PlannedRoute;
import com.galactic.fleet.routeplanning.api.RouteRejectionException;
import com.galactic.fleet.routeplanning.api.RouteRequest;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.util.Objects;
import org.springframework.stereotype.Service;

/**
 * The one synchronous dependency between modules: this module asks {@code routeplanning} for a route
 * through its {@code api} named interface, because the route is part of the HTTP response.
 *
 * <p>It is the single exception to "modules do not share a thread" (ADR-0043). The call is a pure
 * computation — no I/O, no events — and the caller makes it <em>before</em> opening its transaction,
 * so the two modules never share a transaction.
 *
 * <p>The adapter only translates vocabulary: this module's command into the planner's request, and
 * the planner's {@link RouteRejectionException} into {@link RouteRejectedException} (→ 422). What a
 * ship class can fly is the planner's knowledge, not this module's.
 */
@Service
public class RoutePlanner {

    private static final String OBSERVATION_NAME = "reservations.route.plan";
    private static final String METRIC_ROUTE_PLAN_SUCCESS = "reservations.route.plan.success";
    private static final String METRIC_ROUTE_PLAN_ERROR = "reservations.route.plan.errors";

    private final PlanRouteUseCase planRouteUseCase;
    private final ObservationRegistry observationRegistry;
    private final MeterRegistry meterRegistry;
    private final Counter routePlanSuccessCounter;

    public RoutePlanner(
            PlanRouteUseCase planRouteUseCase, MeterRegistry meterRegistry, ObservationRegistry observationRegistry) {
        this.planRouteUseCase = planRouteUseCase;
        this.observationRegistry = observationRegistry;
        this.meterRegistry = meterRegistry;
        this.routePlanSuccessCounter = Counter.builder(METRIC_ROUTE_PLAN_SUCCESS)
                .description("Number of successfully planned routes")
                .register(meterRegistry);
    }

    public Route calculateRoute(ReserveBayCommand command) {
        Objects.requireNonNull(command, "command must not be null");
        if (!command.requestRoute()) {
            return null;
        }
        return Observation.createNotStarted(OBSERVATION_NAME, observationRegistry)
                .lowCardinalityKeyValue("startStarport", Objects.toString(command.startStarportCode(), "unknown"))
                .lowCardinalityKeyValue("destinationStarport", command.destinationStarportCode())
                .observe(() -> plan(command));
    }

    private Route plan(ReserveBayCommand command) {
        PlannedRoute planned;
        try {
            planned = planRouteUseCase.planRoute(RouteRequest.builder()
                    .originPortId(command.startStarportCode())
                    .destinationPortId(command.destinationStarportCode())
                    .shipClass(command.shipClass().name())
                    .build());
        } catch (RouteRejectionException rejection) {
            Counter.builder(METRIC_ROUTE_PLAN_ERROR)
                    .description("Number of route planning attempts the planner refused")
                    .tag("errorType", "domain")
                    .register(meterRegistry)
                    .increment();
            throw new RouteRejectedException(command.startStarportCode(), command.destinationStarportCode());
        }

        routePlanSuccessCounter.increment();
        return Route.builder()
                .routeCode(planned.routeId())
                .startStarportCode(command.startStarportCode())
                .destinationStarportCode(command.destinationStarportCode())
                .etaHours(planned.etaHours())
                .riskScore(planned.riskScore())
                .plannedAt(planned.plannedAt())
                .isActive(true)
                .build();
    }
}
