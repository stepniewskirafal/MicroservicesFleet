package com.galactic.starport.infrastructure.adapters;

import com.galactic.starport.domain.enums.ShipClass;
import java.time.Instant;
import java.util.concurrent.ThreadLocalRandom;

import com.galactic.starport.domain.model.Route;
import com.galactic.starport.domain.port.RoutePlannerPort;
import org.springframework.stereotype.Service;

/**
 * A simple {@link RoutePlannerPort} implementation that returns a random risk score.
 *
 * This implementation can be used during development when the actual HTTP client for
 * the Trade Route Planner service is not yet available. The risk score returned
 * will be a random double between 0.0 and 1.0.  In production this class
 * should be replaced by a real client that performs an HTTP call to the
 * trade-route-planner service and returns the risk score from the response.
 */
@Service
public class RandomRoutePlannerClient implements RoutePlannerPort {

    @Override
    public Route planRoute(
            String originPortId,
            String destinationPortId,
            ShipClass shipClass,
            Instant departureAt) {

        return Route.builder()
                .etaLY(ThreadLocalRandom.current().nextDouble())
                .riskScore(ThreadLocalRandom.current().nextDouble())
                .build();
    }
}