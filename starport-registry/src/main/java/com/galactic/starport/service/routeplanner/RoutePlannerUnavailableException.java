package com.galactic.starport.service.routeplanner;

/**
 * The planner did not answer usefully: HTTP 5xx, timeout, connection failure, empty body or an
 * open circuit. This is a transient infrastructure failure — the only thing retry retries and
 * the only thing the circuit breaker records as failure (see the {@code resilience4j} section
 * in application.yml). Mapped to HTTP 503 + {@code Retry-After} so callers know to come back.
 */
public class RoutePlannerUnavailableException extends RouteUnavailableException {

    private static final long serialVersionUID = 1L;

    /** Planner's HTTP status, or {@link #NO_RESPONSE} when nothing came back at all. */
    public static final int NO_RESPONSE = 0;

    private final int statusCode;

    public RoutePlannerUnavailableException(String originPortId, String destinationPortId, int statusCode) {
        super(describe(originPortId, destinationPortId, reason(statusCode)));
        this.statusCode = statusCode;
    }

    public RoutePlannerUnavailableException(String originPortId, String destinationPortId, Throwable cause) {
        super(describe(originPortId, destinationPortId, cause.getClass().getSimpleName()), cause);
        this.statusCode = NO_RESPONSE;
    }

    private static String reason(int statusCode) {
        return statusCode == NO_RESPONSE
                ? "planner returned no usable response"
                : "planner failed with HTTP " + statusCode;
    }

    public int getStatusCode() {
        return statusCode;
    }
}
