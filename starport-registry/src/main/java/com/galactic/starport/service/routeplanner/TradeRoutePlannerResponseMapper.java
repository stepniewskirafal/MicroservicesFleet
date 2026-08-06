package com.galactic.starport.service.routeplanner;

import com.galactic.starport.service.Route;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * DTO-to-domain mapping for the planner's answer.
 *
 * <p>Lives outside {@link TradeRoutePlannerClient} on purpose: the client is wrapped by the
 * resilience proxy, and a bug in here must not be retried, must not be recorded by the breaker
 * and must not reach the caller as "planner unavailable". It is a bean (not a static helper) so
 * that a test can replace it with one that throws and prove exactly that.
 */
@Component
@Slf4j
class TradeRoutePlannerResponseMapper {

    Route toRoute(TradeRoutePlannerResponse response, String origin, String destination) {
        // DEBUG, not INFO: this fires on every healthy call. The happy path must be quieter
        // than the failure path, not louder.
        log.debug(
                "Route planned: {} from {} to {} — eta={}h risk={}",
                response.routeId(),
                origin,
                destination,
                response.etaHours(),
                response.riskScore());

        return Route.builder()
                .routeCode(response.routeId())
                .startStarportCode(origin)
                .destinationStarportCode(destination)
                .etaHours(response.etaHours())
                .riskScore(response.riskScore())
                .isActive(true)
                .build();
    }
}
