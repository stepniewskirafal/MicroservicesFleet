package com.galactic.starport.service.routeplanner;

import static org.assertj.core.api.Assertions.assertThat;

import com.galactic.starport.service.ReserveBayCommand;
import com.galactic.starport.service.Route;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

@Execution(ExecutionMode.CONCURRENT)
class RoutePlannerServiceTest {

    private RoutePlannerService routePlanner;

    @BeforeEach
    void setUp() {
        routePlanner = new RoutePlannerService(new SimpleMeterRegistry(), ObservationRegistry.NOOP);
    }

    @Test
    void should_return_route_when_route_requested() {
        // given
        ReserveBayCommand cmd = aCommand(true);

        // when
        Route route = routePlanner.calculateRoute(cmd);

        // then
        assertThat(route).isNotNull();
        assertThat(route.getRouteCode()).contains("ROUTE-ALPHA-BASE-DEF");
        assertThat(route.getStartStarportCode()).isEqualTo("ALPHA-BASE");
        assertThat(route.getDestinationStarportCode()).isEqualTo("DEF");
        assertThat(route.getEtaLightYears()).isPositive();
        assertThat(route.getRiskScore()).isBetween(0.0, 1.0);
        assertThat(route.isActive()).isTrue();
    }

    @Test
    void should_return_null_when_route_not_requested() {
        // given
        ReserveBayCommand cmd = aCommand(false);

        // when
        Route route = routePlanner.calculateRoute(cmd);

        // then
        assertThat(route).isNull();
    }

    @Test
    void should_include_start_and_destination_in_route_code() {
        // given
        ReserveBayCommand cmd = ReserveBayCommand.builder()
                .destinationStarportCode("OMEGA")
                .startStarportCode("ZETA")
                .customerCode("CUST-001")
                .shipCode("SS-001")
                .shipClass(ReserveBayCommand.ShipClass.CRUISER)
                .startAt(Instant.parse("2008-01-01T00:00:00Z"))
                .endAt(Instant.parse("2008-01-01T01:00:00Z"))
                .requestRoute(true)
                .build();

        // when
        Route route = routePlanner.calculateRoute(cmd);

        // then
        assertThat(route.getRouteCode()).startsWith("ROUTE-ZETA-OMEGA-");
    }

    private static ReserveBayCommand aCommand(boolean requestRoute) {
        return ReserveBayCommand.builder()
                .destinationStarportCode("DEF")
                .startStarportCode("ALPHA-BASE")
                .customerCode("CUST-001")
                .shipCode("SS-001")
                .shipClass(ReserveBayCommand.ShipClass.SCOUT)
                .startAt(Instant.parse("2008-01-01T00:00:00Z"))
                .endAt(Instant.parse("2008-01-01T01:00:00Z"))
                .requestRoute(requestRoute)
                .build();
    }
}
