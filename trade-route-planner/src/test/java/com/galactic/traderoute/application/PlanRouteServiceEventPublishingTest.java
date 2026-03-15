package com.galactic.traderoute.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.galactic.traderoute.domain.model.PlannedRoute;
import com.galactic.traderoute.domain.model.RoutePlannedEvent;
import com.galactic.traderoute.domain.model.RouteRequest;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

/**
 * Verifies that {@link PlanRouteService} correctly publishes events
 * and records distribution summary metrics — gaps not covered by the existing tests.
 */
@Execution(ExecutionMode.CONCURRENT)
class PlanRouteServiceEventPublishingTest {

    private SimpleMeterRegistry meterRegistry;
    private List<RoutePlannedEvent> publishedEvents;
    private PlanRouteService service;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        publishedEvents = new ArrayList<>();
        service = new PlanRouteService(meterRegistry, ObservationRegistry.NOOP, publishedEvents::add);
    }

    @Test
    void should_publish_event_with_matching_route_id() {
        PlannedRoute route = service.planRoute(aRequest("SCOUT", 10.0));

        assertThat(publishedEvents).hasSize(1);
        assertThat(publishedEvents.getFirst().routeId()).isEqualTo(route.routeId());
    }

    @Test
    void should_publish_event_with_origin_and_destination() {
        service.planRoute(aRequest("SP-A", "SP-B", "FREIGHTER", 20.0));

        RoutePlannedEvent event = publishedEvents.getFirst();
        assertThat(event.originPortId()).isEqualTo("SP-A");
        assertThat(event.destinationPortId()).isEqualTo("SP-B");
    }

    @Test
    void should_publish_event_with_ship_class_and_scores() {
        PlannedRoute route = service.planRoute(aRequest("CRUISER", 15.0));

        RoutePlannedEvent event = publishedEvents.getFirst();
        assertThat(event.shipClass()).isEqualTo("CRUISER");
        assertThat(event.etaHours()).isEqualTo(route.etaHours());
        assertThat(event.riskScore()).isEqualTo(route.riskScore());
    }

    @Test
    void should_publish_event_with_non_null_planned_at() {
        service.planRoute(aRequest("SCOUT", 5.0));

        assertThat(publishedEvents.getFirst().plannedAt()).isNotNull();
    }

    @Test
    void should_generate_unique_route_ids_across_calls() {
        PlannedRoute r1 = service.planRoute(aRequest("SCOUT", 10.0));
        PlannedRoute r2 = service.planRoute(aRequest("SCOUT", 10.0));

        assertThat(r1.routeId()).isNotEqualTo(r2.routeId());
    }

    @Test
    void should_record_risk_score_distribution_summary() {
        service.planRoute(aRequest("SP-X", "SP-Y", "SCOUT", 10.0));

        DistributionSummary summary = meterRegistry.get("routes.risk.score").summary();

        assertThat(summary.count()).isEqualTo(1);
        assertThat(summary.totalAmount()).isBetween(0.0, 1.0);
    }

    @Test
    void should_record_eta_hours_distribution_summary() {
        service.planRoute(aRequest("FREIGHTER", 10.0));

        DistributionSummary summary = meterRegistry.get("routes.eta.hours")
                .tag("shipClass", "FREIGHTER")
                .summary();

        assertThat(summary.count()).isEqualTo(1);
        assertThat(summary.totalAmount()).isBetween(18.0, 28.0);
    }

    @Test
    void should_handle_case_insensitive_ship_class() {
        PlannedRoute route = service.planRoute(aRequest("scout", 10.0));

        assertThat(route.etaHours()).isBetween(8.0, 18.0);
    }

    private static RouteRequest aRequest(String shipClass, double fuelRangeLY) {
        return aRequest("SP-A", "SP-B", shipClass, fuelRangeLY);
    }

    private static RouteRequest aRequest(String origin, String dest, String shipClass, double fuelRangeLY) {
        return RouteRequest.builder()
                .originPortId(origin)
                .destinationPortId(dest)
                .shipClass(shipClass)
                .fuelRangeLY(fuelRangeLY)
                .build();
    }
}
