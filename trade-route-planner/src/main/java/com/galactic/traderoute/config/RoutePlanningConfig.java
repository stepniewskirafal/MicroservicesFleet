package com.galactic.traderoute.config;

import com.galactic.traderoute.application.PlanRouteService;
import com.galactic.traderoute.port.in.PlanRouteUseCase;
import com.galactic.traderoute.port.out.RouteEventPublisher;
import com.galactic.traderoute.port.out.RouteMetricsPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Composition root for the application core. Wires the framework-free {@link PlanRouteService} as a
 * bean so the core itself carries no Spring stereotype — the application/domain layer must not
 * depend on the framework (hexagonal architecture).
 */
@Configuration
class RoutePlanningConfig {

    @Bean
    PlanRouteUseCase planRouteService(RouteMetricsPort routeMetricsPort, RouteEventPublisher routeEventPublisher) {
        return new PlanRouteService(routeMetricsPort, routeEventPublisher);
    }
}
