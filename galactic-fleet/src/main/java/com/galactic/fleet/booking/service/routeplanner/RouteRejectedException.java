package com.galactic.fleet.booking.service.routeplanner;

/**
 * The {@code routeplanning} module refused to plan this route (e.g. insufficient fuel range). It is
 * a deterministic domain decision, not a failure, and maps to HTTP 422.
 */
public class RouteRejectedException extends RuntimeException {

    public RouteRejectedException(String originPortId, String destinationPortId) {
        super("Route rejected from " + originPortId + " to " + destinationPortId);
    }
}
