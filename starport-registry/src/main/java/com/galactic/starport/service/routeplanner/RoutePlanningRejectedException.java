package com.galactic.starport.service.routeplanner;

/**
 * The planner answered and REFUSED (HTTP 4xx): a deterministic domain rejection for this
 * origin/destination/ship profile. A second identical request cannot produce a different
 * answer, so retry must not retry it and the circuit breaker ignores it (see the
 * {@code resilience4j} section in application.yml) — a wave of rejections is not an
 * infrastructure failure and must not open the circuit against a healthy planner.
 */
public class RoutePlanningRejectedException extends RouteUnavailableException {

    private static final long serialVersionUID = 1L;

    /** Status the planner answered with, e.g. 422. Carried into the message so it reaches logs and the API body. */
    private final int statusCode;

    public RoutePlanningRejectedException(String originPortId, String destinationPortId, int statusCode) {
        super(describe(originPortId, destinationPortId, "planner rejected with HTTP " + statusCode));
        this.statusCode = statusCode;
    }

    public int getStatusCode() {
        return statusCode;
    }
}
