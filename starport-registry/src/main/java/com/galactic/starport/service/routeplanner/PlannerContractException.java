package com.galactic.starport.service.routeplanner;

/**
 * The planner answered with a 4xx that is NOT its deliberate domain rejection (422 with error
 * code {@code ROUTE_REJECTED}). Every other 4xx — a 400 from our own broken serializer, a 404 from
 * a wrong URL, a 422 rejecting the shape of our DTO — says nothing about the planner's health and
 * nothing about the customer's request: it is OUR contract bug or OUR misconfiguration.
 *
 * <p>Deliberately NOT a {@link RouteUnavailableException}: the 409 "change your request" handler
 * and the {@code route_unavailable} metric must not see it. Deliberately on NO resilience4j list:
 * not retried (asking again cannot fix our code), not recorded as a planner failure. Mapped to HTTP
 * 502 by {@code GlobalExceptionHandler} and counted as {@code outcome="misrouted"} — a counter that
 * a healthy system keeps at zero, which is exactly what the {@code TradeRoutePlannerUnexpectedOutcome}
 * alert watches (ADR-0040).
 */
public class PlannerContractException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final int statusCode;

    public PlannerContractException(String originPortId, String destinationPortId, int statusCode, String bodySnippet) {
        super("Planner answered HTTP " + statusCode + " outside its contract for route " + originPortId + " -> "
                + destinationPortId + " (body: " + bodySnippet + ")");
        this.statusCode = statusCode;
    }

    public int getStatusCode() {
        return statusCode;
    }
}
