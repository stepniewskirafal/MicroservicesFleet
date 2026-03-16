package com.galactic.traderoute.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.galactic.traderoute.domain.model.PlannedRoute;
import com.galactic.traderoute.domain.model.RouteRejectionException;
import com.galactic.traderoute.domain.model.RouteRequest;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

@Execution(ExecutionMode.CONCURRENT)
class PlanRouteServiceTest {

    private SimpleMeterRegistry meterRegistry;
    private PlanRouteService service;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        service = new PlanRouteService(meterRegistry, ObservationRegistry.NOOP, event -> {});
    }

    @Test
    void should_return_planned_route_with_valid_request() {
        RouteRequest request = aRequest("SP-ORIGIN", "SP-DEST", "FREIGHTER", 25.0);

        PlannedRoute route = service.planRoute(request);

        assertThat(route).isNotNull();
        assertThat(route.routeId()).startsWith("ROUTE-");
        assertThat(route.etaHours()).isPositive();
        assertThat(route.riskScore()).isBetween(0.0, 1.0);
    }

    @Test
    void should_increment_planned_counter_on_success() {
        service.planRoute(aRequest("SP-A", "SP-B", "SCOUT", 15.0));

        Counter counter = meterRegistry.get("routes.planned.count").counter();
        assertThat(counter.count()).isEqualTo(1.0);
    }

    @Test
    void should_reject_route_when_fuel_range_too_low() {
        RouteRequest request = aRequest("SP-A", "SP-B", "SCOUT", 0.5);

        assertThatThrownBy(() -> service.planRoute(request))
                .isInstanceOf(RouteRejectionException.class)
                .hasMessageContaining("INSUFFICIENT_RANGE");
    }

    @Test
    void should_increment_rejected_counter_when_route_rejected() {
        RouteRequest request = aRequest("SP-A", "SP-B", "SCOUT", 0.5);

        try {
            service.planRoute(request);
        } catch (RouteRejectionException ignored) {
        }

        Counter counter = meterRegistry.get("routes.rejected.count").counter();
        assertThat(counter.count()).isEqualTo(1.0);
    }

    @Test
    void should_compute_higher_eta_for_freighter_than_scout() {
        RouteRequest scout = aRequest("SP-A", "SP-B", "SCOUT", 20.0);
        RouteRequest freighter = aRequest("SP-A", "SP-B", "FREIGHTER", 20.0);

        double scoutEta = service.planRoute(scout).etaHours();
        double freighterEta = service.planRoute(freighter).etaHours();

        assertThat(scoutEta).isBetween(8.0, 18.0);
        assertThat(freighterEta).isBetween(18.0, 28.0);
    }

    private static RouteRequest aRequest(String origin, String destination, String shipClass, double fuelRangeLY) {
        return RouteRequest.builder()
                .originPortId(origin)
                .destinationPortId(destination)
                .shipClass(shipClass)
                .fuelRangeLY(fuelRangeLY)
                .build();
    }
}
