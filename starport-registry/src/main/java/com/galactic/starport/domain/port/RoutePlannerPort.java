package com.galactic.starport.domain.port;

public interface RoutePlannerPort {
    /** Zwraca routeId albo null. */
    String requestRoute(String shipId, String originPortCode, String destinationPortCode);
}
