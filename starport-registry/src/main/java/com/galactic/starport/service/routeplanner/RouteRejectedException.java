package com.galactic.starport.service.routeplanner;

/**
 * A DOMAIN rejection from the trade-route-planner: the planner responded (HTTP 4xx) that this specific
 * route cannot be planned (e.g. hazard zone, insufficient range). The planner is healthy — it made a
 * deterministic decision — so unlike {@link RouteUnavailableException} (an infrastructure failure: 5xx,
 * timeout, no instance) this is NOT retried and does NOT count toward the circuit breaker. It maps to
 * HTTP 422 for the caller, whereas an unavailable planner maps to 409.
 */
public class RouteRejectedException extends RuntimeException {

    public RouteRejectedException(String originPortId, String destinationPortId) {
        super("Route rejected from " + originPortId + " to " + destinationPortId);
    }
}
