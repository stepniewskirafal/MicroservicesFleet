package com.galactic.fleet.booking.service.routeplanner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.galactic.fleet.booking.service.ReserveBayCommand;
import com.galactic.fleet.booking.service.Route;
import com.galactic.fleet.routeplanning.api.PlanRouteUseCase;
import com.galactic.fleet.routeplanning.api.PlannedRoute;
import com.galactic.fleet.routeplanning.api.RouteRejectionException;
import com.galactic.fleet.routeplanning.api.RouteRequest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.tck.TestObservationRegistry;
import io.micrometer.observation.tck.TestObservationRegistryAssert;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.mockito.ArgumentCaptor;

/**
 * The seam between the {@code booking} and {@code routeplanning} modules. The other module is
 * mocked at its API ({@link PlanRouteUseCase}) — exactly what the HTTP stub did for the planner
 * service before the fleet became one application.
 */
@Execution(ExecutionMode.CONCURRENT)
class RoutePlanningModuleAdapterTest {

    private PlanRouteUseCase planRouteUseCase;
    private SimpleMeterRegistry meterRegistry;
    private TestObservationRegistry observationRegistry;
    private RoutePlanner adapter;

    @BeforeEach
    void setUp() {
        planRouteUseCase = mock(PlanRouteUseCase.class);
        meterRegistry = new SimpleMeterRegistry();
        observationRegistry = TestObservationRegistry.create();
        adapter = new RoutePlanner(planRouteUseCase, meterRegistry, observationRegistry);
    }

    @Test
    void no_route_requested_means_the_other_module_is_never_called() {
        Route route = adapter.calculateRoute(aCommand(ReserveBayCommand.ShipClass.SCOUT, false));

        assertThat(route).isNull();
        verifyNoInteractions(planRouteUseCase);
    }

    @Test
    void planned_route_is_translated_into_the_reservations_model() {
        given(planRouteUseCase.planRoute(any()))
                .willReturn(PlannedRoute.builder()
                        .routeId("ROUTE-ABCD1234")
                        .etaHours(18.7)
                        .riskScore(0.32)
                        .build());

        Route route = adapter.calculateRoute(aCommand(ReserveBayCommand.ShipClass.FREIGHTER, true));

        assertThat(route.getRouteCode()).isEqualTo("ROUTE-ABCD1234");
        assertThat(route.getStartStarportCode()).isEqualTo("ALPHA-BASE");
        assertThat(route.getDestinationStarportCode()).isEqualTo("DEF");
        assertThat(route.getEtaHours()).isEqualTo(18.7);
        assertThat(route.getRiskScore()).isEqualTo(0.32);
        assertThat(route.isActive()).isTrue();
        assertThat(meterRegistry
                        .get("reservations.route.plan.success")
                        .counter()
                        .count())
                .isEqualTo(1.0);
        TestObservationRegistryAssert.assertThat(observationRegistry)
                .hasObservationWithNameEqualTo("reservations.route.plan")
                .that()
                .hasBeenStopped()
                .hasLowCardinalityKeyValue("startStarport", "ALPHA-BASE")
                .hasLowCardinalityKeyValue("destinationStarport", "DEF");
    }

    @Test
    void request_carries_only_ports_and_ship_class() {
        // What a ship class can fly (fuel range) is the planner's knowledge — the request names the
        // class and nothing more (ADR-0046).
        given(planRouteUseCase.planRoute(any()))
                .willReturn(PlannedRoute.builder()
                        .routeId("ROUTE-1")
                        .etaHours(1)
                        .riskScore(0)
                        .build());
        ArgumentCaptor<RouteRequest> request = ArgumentCaptor.forClass(RouteRequest.class);

        adapter.calculateRoute(aCommand(ReserveBayCommand.ShipClass.FREIGHTER, true));

        org.mockito.Mockito.verify(planRouteUseCase).planRoute(request.capture());
        assertThat(request.getValue()).isEqualTo(new RouteRequest("ALPHA-BASE", "DEF", "FREIGHTER"));
    }

    @Test
    void planned_at_of_the_route_is_carried_over() {
        Instant plannedAt = Instant.parse("2030-01-01T00:00:00Z");
        given(planRouteUseCase.planRoute(any()))
                .willReturn(PlannedRoute.builder()
                        .routeId("ROUTE-1")
                        .etaHours(1)
                        .riskScore(0)
                        .plannedAt(plannedAt)
                        .build());

        Route route = adapter.calculateRoute(aCommand(ReserveBayCommand.ShipClass.SCOUT, true));

        assertThat(route.getPlannedAt()).isEqualTo(plannedAt);
    }

    @Test
    void domain_rejection_becomes_route_rejected_and_is_counted_as_domain() {
        given(planRouteUseCase.planRoute(any()))
                .willThrow(new RouteRejectionException("INSUFFICIENT_RANGE", "Fuel too low"));

        assertThatThrownBy(() -> adapter.calculateRoute(aCommand(ReserveBayCommand.ShipClass.SCOUT, true)))
                .isInstanceOf(RouteRejectedException.class)
                .hasMessageContaining("ALPHA-BASE")
                .hasMessageContaining("DEF");

        assertThat(errorCount("domain")).isEqualTo(1.0);
        assertThat(meterRegistry
                        .find("reservations.route.plan.success")
                        .counter()
                        .count())
                .isZero();
    }

    @Test
    void any_other_failure_is_a_bug_and_propagates_unchanged() {
        // The planner does no I/O, so there is no "unavailable" outcome to translate any more.
        IllegalStateException bug = new IllegalStateException("planner bug");
        given(planRouteUseCase.planRoute(any())).willThrow(bug);

        assertThatThrownBy(() -> adapter.calculateRoute(aCommand(ReserveBayCommand.ShipClass.SCOUT, true)))
                .isSameAs(bug);

        assertThat(meterRegistry
                        .find("reservations.route.plan.success")
                        .counter()
                        .count())
                .isZero();
    }

    private double errorCount(String errorType) {
        return meterRegistry
                .get("reservations.route.plan.errors")
                .tag("errorType", errorType)
                .counter()
                .count();
    }

    private static ReserveBayCommand aCommand(ReserveBayCommand.ShipClass shipClass, boolean requestRoute) {
        return ReserveBayCommand.builder()
                .destinationStarportCode("DEF")
                .startStarportCode("ALPHA-BASE")
                .customerCode("CUST-001")
                .shipCode("SS-001")
                .shipClass(shipClass)
                .startAt(Instant.parse("2030-01-01T00:00:00Z"))
                .endAt(Instant.parse("2030-01-01T01:00:00Z"))
                .requestRoute(requestRoute)
                .build();
    }
}
