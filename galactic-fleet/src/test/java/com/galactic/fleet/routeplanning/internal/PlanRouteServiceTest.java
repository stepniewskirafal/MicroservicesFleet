package com.galactic.fleet.routeplanning.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.galactic.fleet.routeplanning.api.PlannedRoute;
import com.galactic.fleet.routeplanning.api.RouteRejectionException;
import com.galactic.fleet.routeplanning.api.RouteRequest;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

@Execution(ExecutionMode.CONCURRENT)
class PlanRouteServiceTest {

    /** A ship class the planner knows as unable to reach the minimum range. */
    private static final String SHORT_RANGE_CLASS = "TUG";

    private SimpleMeterRegistry meterRegistry;
    private PlanRouteService service;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        service = new PlanRouteService(
                new MicrometerRouteMetrics(meterRegistry, ObservationRegistry.NOOP),
                new ShipFuelRanges(Map.of("SCOUT", 15.0, "FREIGHTER", 25.0, SHORT_RANGE_CLASS, 0.5), 5.0));
    }

    @Test
    void should_return_planned_route_with_valid_request() {
        Instant before = Instant.now();

        PlannedRoute route = service.planRoute(aRequest("SP-ORIGIN", "SP-DEST", "FREIGHTER"));

        assertThat(route).isNotNull();
        assertThat(route.routeId()).startsWith("ROUTE-");
        assertThat(route.etaHours()).isPositive();
        assertThat(route.riskScore()).isBetween(0.0, 1.0);
        assertThat(route.plannedAt()).isBetween(before, Instant.now());
    }

    @Test
    void should_increment_planned_counter_on_success() {
        service.planRoute(aRequest("SP-A", "SP-B", "SCOUT"));

        Counter counter = meterRegistry.get("routes.planned.count").counter();
        assertThat(counter.count()).isEqualTo(1.0);
    }

    @Test
    void should_reject_route_when_the_ship_class_cannot_fly_the_minimum_range() {
        assertThatThrownBy(() -> service.planRoute(aRequest("SP-A", "SP-B", SHORT_RANGE_CLASS)))
                .isInstanceOf(RouteRejectionException.class)
                .hasMessageContaining("INSUFFICIENT_RANGE");
    }

    @Test
    void should_increment_rejected_counter_and_not_the_planned_one_when_route_rejected() {
        try {
            service.planRoute(aRequest("SP-A", "SP-B", SHORT_RANGE_CLASS));
        } catch (RouteRejectionException ignored) {
        }

        Counter rejected = meterRegistry.get("routes.rejected.count").counter();
        assertThat(rejected.count()).isEqualTo(1.0);
        assertThat(meterRegistry.get("routes.planned.count").counter().count()).isZero();
    }

    @Test
    void should_compute_higher_eta_for_freighter_than_scout() {
        double scoutEta = service.planRoute(aRequest("SP-A", "SP-B", "SCOUT")).etaHours();
        double freighterEta =
                service.planRoute(aRequest("SP-A", "SP-B", "FREIGHTER")).etaHours();

        assertThat(scoutEta).isBetween(8.0, 18.0);
        assertThat(freighterEta).isBetween(18.0, 28.0);
    }

    private static RouteRequest aRequest(String origin, String destination, String shipClass) {
        return RouteRequest.builder()
                .originPortId(origin)
                .destinationPortId(destination)
                .shipClass(shipClass)
                .build();
    }
}
