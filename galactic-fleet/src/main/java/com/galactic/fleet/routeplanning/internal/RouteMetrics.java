package com.galactic.fleet.routeplanning.internal;

import com.galactic.fleet.routeplanning.api.PlannedRoute;
import com.galactic.fleet.routeplanning.api.RouteRequest;
import java.util.function.Supplier;

/**
 * Outbound observability port for route planning. Keeps Micrometer (meters, timers, spans) out of
 * the application core: the core states WHAT happened — a route was planned or rejected — and the
 * adapter decides HOW it is measured.
 */
public interface RouteMetrics {

    /** Observe a planning attempt (span + timer), tagged with the request's routing context. */
    PlannedRoute observePlan(RouteRequest request, Supplier<PlannedRoute> planning);

    /**
     * Observe a named sub-step of a planning attempt as a child span of {@link #observePlan}, so a
     * slow plan can be attributed to a phase in Tempo (e.g. {@code routes.plan.compute}).
     */
    <T> T observeStep(String stepName, Supplier<T> step);

    /** Record a successfully planned route: success count and risk / ETA distributions. */
    void recordPlanned(RouteRequest request, PlannedRoute route);

    /** Record a rejected planning attempt, tagged with the rejection reason. */
    void recordRejected(RouteRequest request, String reason);
}
