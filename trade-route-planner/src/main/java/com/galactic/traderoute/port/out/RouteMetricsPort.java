package com.galactic.traderoute.port.out;

import com.galactic.traderoute.domain.model.PlannedRoute;
import com.galactic.traderoute.domain.model.RouteRequest;
import java.util.function.Supplier;

/**
 * Outbound observability port for route planning. Keeps Micrometer (meters, timers, spans) out of
 * the application core: the core states WHAT happened — a route was planned or rejected — and the
 * adapter decides HOW it is measured.
 */
public interface RouteMetricsPort {

    /** Observe a planning attempt (span + timer), tagged with the request's routing context. */
    PlannedRoute observePlan(RouteRequest request, Supplier<PlannedRoute> planning);

    /** Record a successfully planned route: success count and risk / ETA distributions. */
    void recordPlanned(RouteRequest request, PlannedRoute route);

    /** Record a rejected planning attempt, tagged with the rejection reason. */
    void recordRejected(RouteRequest request, String reason);

    /**
     * Record that a route was computed but its {@code RoutePlannedEvent} failed to publish — the
     * dual-write gap (no outbox here yet). Distinct from a success: the route never reached telemetry.
     */
    void recordPublishFailed(RouteRequest request);
}
