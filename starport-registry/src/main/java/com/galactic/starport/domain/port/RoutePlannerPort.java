package com.galactic.starport.domain.port;

import com.galactic.starport.domain.enums.ShipClass;
import com.galactic.starport.domain.model.Route;

import java.time.Instant;

/**
 * Client responsible for interacting with the external Trade Route Planner service.
 *
 * Implementations of this interface should encapsulate the HTTP call (or
 * message-based interaction) with the trade route planner and return a risk score
 * for the requested route. The risk score is used to adjust the final tariff.
 *
 * The method may throw an exception to indicate that the route is unavailable
 * or that the remote service encountered an error. Implementations should
 * consider idempotency and retry strategies where appropriate.
 */
public interface RoutePlannerPort {
    /**
     * Plan a trade route from origin to destination for the given ship profile.
     *
     * @param originPortId       code of the origin starport
     * @param destinationPortId  code of the destination starport
     * @param shipClass          class of the ship requesting the route
     * @param departureAt        desired departure time
     * @return risk score as a value between 0 and 1; higher values indicate higher risk
     * @throws Exception         when the route cannot be planned or the remote service fails
     */
    Route planRoute(String originPortId, String destinationPortId, ShipClass shipClass, Instant departureAt) throws Exception;
}