package com.galactic.traderoute.port.in;

import com.galactic.traderoute.domain.model.PlannedRoute;
import com.galactic.traderoute.domain.model.RouteRequest;

public interface PlanRouteUseCase {
    PlannedRoute planRoute(RouteRequest request);
}
