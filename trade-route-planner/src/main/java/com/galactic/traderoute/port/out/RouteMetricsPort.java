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

    /**
     * Observe a named sub-step of a planning attempt as a child span of {@link #observePlan}. Lets a
     * single "slow" plan be broken down in Tempo (e.g. {@code routes.plan.compute} vs
     * {@code routes.plan.publish}) so the latency is attributable to a phase, not the whole method.
     */
    <T> T observeStep(String stepName, Supplier<T> step);

    /** Void overload of {@link #observeStep(String, Supplier)} for steps that produce no value. */
    default void observeStep(String stepName, Runnable step) {
        observeStep(stepName, () -> {
            step.run();
            return null;
        });
    }

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
