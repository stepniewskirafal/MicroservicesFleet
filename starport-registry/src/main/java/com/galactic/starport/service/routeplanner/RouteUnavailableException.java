package com.galactic.starport.service.routeplanner;

public class RouteUnavailableException extends RuntimeException {

    public RouteUnavailableException(String originPortId, String destinationPortId) {
        super("Cannot plan route from " + originPortId + " to " + destinationPortId);
    }

    public RouteUnavailableException(String originPortId, String destinationPortId, Throwable cause) {
        super("Cannot plan route from " + originPortId + " to " + destinationPortId, cause);
    }
}
