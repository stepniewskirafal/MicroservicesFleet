package com.galactic.router.domain;

public interface RoutePlanningUseCase {
    PlannedRoute planRoute(PlanRouteCommand command);
}
