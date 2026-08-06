package com.galactic.starport.service.routeplanner;

public class RouteUnavailableException extends RuntimeException {

    public RouteUnavailableException(String originPortId, String destinationPortId) {
        super("Cannot plan route from " + originPortId + " to " + destinationPortId);
    }

    public RouteUnavailableException(String originPortId, String destinationPortId, Throwable cause) {
        super("Cannot plan route from " + originPortId + " to " + destinationPortId, cause);
    }

    /** For subtypes that enrich the message with what the planner actually answered. */
    protected RouteUnavailableException(String message) {
        super(message);
    }

    protected RouteUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }

    protected static String describe(String originPortId, String destinationPortId, String detail) {
        return "Cannot plan route from " + originPortId + " to " + destinationPortId + " (" + detail + ")";
    }
}
