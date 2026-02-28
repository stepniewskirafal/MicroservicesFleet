package com.galactic.traderoute.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.galactic.traderoute.domain.model.PlannedRoute;
import com.galactic.traderoute.domain.model.RouteRejectionException;
import com.galactic.traderoute.domain.model.RouteRequest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Parameterized unit tests for ETA computation and validation rules in PlanRouteService.
 * Covers all ship classes and boundary conditions — important for mutation testing coverage.
 */
@Execution(ExecutionMode.CONCURRENT)
class EtaComputationParameterizedTest {

    private final PlanRouteService service =
            new PlanRouteService(new SimpleMeterRegistry(), ObservationRegistry.NOOP);

    @ParameterizedTest(name = "[{index}] shipClass={0} → ETA in [{1}, {2}]")
    @CsvSource({
        "SCOUT,          8.0, 18.0",
        "scout,          8.0, 18.0", // case-insensitive
        "FREIGHTER,     18.0, 28.0",
        "FREIGHTER_MK2, 18.0, 28.0",
        "CRUISER,       12.0, 22.0",
        "DESTROYER,     20.0, 30.0", // unknown class
        "DREADNOUGHT,   20.0, 30.0", // another unknown class
    })
    void should_compute_eta_within_expected_bounds(String shipClass, double minEta, double maxEta) {
        RouteRequest request = aRequest(shipClass, 20.0);

        PlannedRoute route = service.planRoute(request);

        assertThat(route.etaHours())
                .as("ETA for %s should be between %.1f and %.1f", shipClass, minEta, maxEta)
                .isBetween(minEta, maxEta);
    }

    @ParameterizedTest(name = "[{index}] fuelRangeLY={0} is too low → rejection")
    @ValueSource(doubles = {0.0, 0.5, 0.99, -1.0, -100.0})
    void should_reject_when_fuel_range_below_minimum(double fuelRangeLY) {
        RouteRequest request = aRequest("SCOUT", fuelRangeLY);

        assertThatThrownBy(() -> service.planRoute(request))
                .isInstanceOf(RouteRejectionException.class)
                .hasMessageContaining("INSUFFICIENT_RANGE");
    }

    @ParameterizedTest(name = "[{index}] fuelRangeLY={0} meets minimum → success")
    @ValueSource(doubles = {1.0, 1.001, 5.0, 25.0, 1000.0})
    void should_accept_when_fuel_range_meets_minimum(double fuelRangeLY) {
        RouteRequest request = aRequest("SCOUT", fuelRangeLY);

        PlannedRoute route = service.planRoute(request);

        assertThat(route).isNotNull();
        assertThat(route.routeId()).startsWith("ROUTE-");
    }

    private RouteRequest aRequest(String shipClass, double fuelRangeLY) {
        return RouteRequest.builder()
                .originPortId("SP-A")
                .destinationPortId("SP-B")
                .shipClass(shipClass)
                .fuelRangeLY(fuelRangeLY)
                .build();
    }
}
