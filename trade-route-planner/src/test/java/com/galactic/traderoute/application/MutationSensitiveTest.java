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

/**
 * Tests designed to maximise PITest mutation kill rate.
 *
 * <p>Each test targets a specific logical boundary that common mutators (CONDITIONALS_BOUNDARY,
 * MATH, NEGATE_CONDITIONALS, INCREMENTS) would alter. Using exact numeric assertions rather than
 * ranges wherever the domain logic is deterministic.
 */
@Execution(ExecutionMode.CONCURRENT)
class MutationSensitiveTest {

    private static final double MIN_FUEL_RANGE_LY = 1.0;

    private SimpleMeterRegistry meterRegistry;
    private PlanRouteService service;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        service = new PlanRouteService(meterRegistry, ObservationRegistry.NOOP);
    }

    // ── Fuel-range boundary (kills CONDITIONALS_BOUNDARY mutants) ─────────────

    @Test
    void should_accept_exactly_minimum_fuel_range() {
        // Exactly at the boundary — a ">=" mutated to ">" would fail this test.
        PlannedRoute route = service.planRoute(aRequest("SCOUT", MIN_FUEL_RANGE_LY));

        assertThat(route).isNotNull();
    }

    @Test
    void should_reject_just_below_minimum_fuel_range() {
        // 0.999… just below the boundary.
        double belowMin = MIN_FUEL_RANGE_LY - 0.001;

        assertThatThrownBy(() -> service.planRoute(aRequest("SCOUT", belowMin)))
                .isInstanceOf(RouteRejectionException.class)
                .extracting(t -> ((RouteRejectionException) t).getReason())
                .isEqualTo("INSUFFICIENT_RANGE");
    }

    // ── Counter increments (kills INCREMENTS and VOID_METHOD_CALLS mutants) ───

    @Test
    void should_increment_planned_counter_exactly_once_per_success() {
        service.planRoute(aRequest("SCOUT", 10.0));
        service.planRoute(aRequest("FREIGHTER", 10.0));

        Counter counter = meterRegistry.get("routes.planned.count").counter();
        assertThat(counter.count()).isEqualTo(2.0);
    }

    @Test
    void should_increment_rejected_counter_exactly_once_per_rejection() {
        tryPlanAndIgnoreException(aRequest("SCOUT", 0.1));
        tryPlanAndIgnoreException(aRequest("SCOUT", 0.5));
        tryPlanAndIgnoreException(aRequest("FREIGHTER", 0.9));

        Counter counter = meterRegistry.get("routes.rejected.count").counter();
        assertThat(counter.count()).isEqualTo(3.0);
    }

    @Test
    void should_not_increment_rejected_counter_on_success() {
        service.planRoute(aRequest("SCOUT", 10.0));

        Counter counter = meterRegistry.get("routes.rejected.count").counter();
        assertThat(counter.count()).isZero();
    }

    @Test
    void should_not_increment_planned_counter_on_rejection() {
        tryPlanAndIgnoreException(aRequest("SCOUT", 0.5));

        Counter counter = meterRegistry.get("routes.planned.count").counter();
        assertThat(counter.count()).isZero();
    }

    // ── ETA base values (kills MATH mutants on literal arithmetic) ────────────

    @Test
    void scout_eta_lower_bound_is_eight_hours_when_risk_is_zero() {
        // Risk adds riskScore * 10; with risk ≥ 0, ETA ≥ 8.0 for SCOUT.
        // Running many times to catch near-zero risk scores.
        for (int i = 0; i < 50; i++) {
            PlannedRoute route = service.planRoute(aRequest("SCOUT", 10.0));
            assertThat(route.etaHours()).isGreaterThanOrEqualTo(8.0);
        }
    }

    @Test
    void freighter_eta_lower_bound_is_eighteen_hours() {
        for (int i = 0; i < 50; i++) {
            PlannedRoute route = service.planRoute(aRequest("FREIGHTER", 10.0));
            assertThat(route.etaHours()).isGreaterThanOrEqualTo(18.0);
        }
    }

    @Test
    void freighter_mk2_eta_lower_bound_is_eighteen_hours() {
        for (int i = 0; i < 50; i++) {
            PlannedRoute route = service.planRoute(aRequest("FREIGHTER_MK2", 10.0));
            assertThat(route.etaHours()).isGreaterThanOrEqualTo(18.0);
        }
    }

    @Test
    void cruiser_eta_lower_bound_is_twelve_hours() {
        for (int i = 0; i < 50; i++) {
            PlannedRoute route = service.planRoute(aRequest("CRUISER", 10.0));
            assertThat(route.etaHours()).isGreaterThanOrEqualTo(12.0);
        }
    }

    @Test
    void unknown_class_eta_lower_bound_is_twenty_hours() {
        for (int i = 0; i < 50; i++) {
            PlannedRoute route = service.planRoute(aRequest("UNKNOWN_CLASS", 10.0));
            assertThat(route.etaHours()).isGreaterThanOrEqualTo(20.0);
        }
    }

    @Test
    void risk_score_multiplier_adds_at_most_ten_hours() {
        // risk ∈ [0,1), so risk * 10 < 10 → etaHours < baseEta + 10
        for (int i = 0; i < 50; i++) {
            PlannedRoute route = service.planRoute(aRequest("SCOUT", 10.0));
            assertThat(route.etaHours()).isLessThan(18.0 + 1e-9); // 8 + 10 = 18 max (exclusive)
        }
    }

    // ── RouteId format (kills STRING_MUTANTS) ─────────────────────────────────

    @Test
    void should_generate_route_id_with_correct_prefix_and_length() {
        PlannedRoute route = service.planRoute(aRequest("SCOUT", 5.0));

        assertThat(route.routeId())
                .startsWith("ROUTE-")
                .hasSize(14) // "ROUTE-" (6) + 8 hex chars
                .matches("ROUTE-[A-F0-9]{8}");
    }

    // ── RiskScore range (kills MATH mutants on nextDouble bounds) ─────────────

    @Test
    void risk_score_is_always_in_zero_to_one_range() {
        for (int i = 0; i < 100; i++) {
            PlannedRoute route = service.planRoute(aRequest("SCOUT", 10.0));
            assertThat(route.riskScore()).isBetween(0.0, 1.0);
        }
    }

    // ── Helper ─────────────────────────────────────────────────────────────────

    private RouteRequest aRequest(String shipClass, double fuelRangeLY) {
        return RouteRequest.builder()
                .originPortId("SP-A")
                .destinationPortId("SP-B")
                .shipClass(shipClass)
                .fuelRangeLY(fuelRangeLY)
                .build();
    }

    private void tryPlanAndIgnoreException(RouteRequest request) {
        try {
            service.planRoute(request);
        } catch (RouteRejectionException ignored) {
        }
    }
}
