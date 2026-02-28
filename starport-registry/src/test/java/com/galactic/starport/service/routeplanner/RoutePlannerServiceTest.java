package com.galactic.starport.service.routeplanner;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.galactic.starport.BaseAcceptanceTest;
import com.galactic.starport.service.ReserveBayCommand;
import com.galactic.starport.service.Route;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.api.parallel.ResourceAccessMode;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.springframework.beans.factory.annotation.Autowired;

@Execution(ExecutionMode.CONCURRENT)
@ResourceLock(value = "DB_TRUNCATE", mode = ResourceAccessMode.READ)
class RoutePlannerServiceTest extends BaseAcceptanceTest {

    @Autowired
    RoutePlanner routePlanner;

    @Test
    void shouldCalculateRouteWhenRequested() {
        // given
        String originCode = "ALPHA-BASE-ROUTE";
        String destinationCode = "DEF-ROUTE";
        String customerCode = "CUST-ROUTE";
        String shipCode = "SS-Enterprise-ROUTE";

        seedDefaultReservationFixture(
                destinationCode,
                Map.of(
                        "originCode", originCode,
                        "customerCode", customerCode,
                        "shipCode", shipCode,
                        "destinationName", "Alpha Base Central"));

        ReserveBayCommand cmd = ReserveBayCommand.builder()
                .destinationStarportCode(destinationCode)
                .startStarportCode(originCode)
                .customerCode(customerCode)
                .shipCode(shipCode)
                .shipClass(ReserveBayCommand.ShipClass.SCOUT)
                .startAt(Instant.parse("2008-01-01T00:00:00Z"))
                .endAt(Instant.parse("2008-01-01T01:00:00Z"))
                .requestRoute(true)
                .build();

        // when
        Route route = routePlanner.calculateRoute(cmd);

        // then
        assertNotNull(route);
        assertTrue(route.getRouteCode()
                .contains("ROUTE-" + cmd.startStarportCode() + "-" + cmd.destinationStarportCode()));
        assertTrue(route.getStartStarportCode().equals(cmd.startStarportCode()));
        assertTrue(route.getDestinationStarportCode().equals(cmd.destinationStarportCode()));
        assertTrue(route.getEtaLightYears() > 0);
        assertTrue(route.getRiskScore() >= 0);
        assertTrue(route.isActive());
    }

    @Test
    void shouldReturnNullRouteWhenNotRequested() {
        // given
        String originCode = "ALPHA-BASE-NO-ROUTE";
        String destinationCode = "DEF-NO-ROUTE";
        String customerCode = "CUST-NO-ROUTE";
        String shipCode = "SS-Enterprise-NO-ROUTE";

        seedDefaultReservationFixture(
                destinationCode,
                Map.of(
                        "originCode", originCode,
                        "customerCode", customerCode,
                        "shipCode", shipCode,
                        "destinationName", "Alpha Base Central"));

        ReserveBayCommand cmd = ReserveBayCommand.builder()
                .destinationStarportCode(destinationCode)
                .startStarportCode(originCode)
                .customerCode(customerCode)
                .shipCode(shipCode)
                .shipClass(ReserveBayCommand.ShipClass.SCOUT)
                .startAt(Instant.parse("2008-01-01T00:00:00Z"))
                .endAt(Instant.parse("2008-01-01T01:00:00Z"))
                .requestRoute(false)
                .build();

        // when
        Route route = routePlanner.calculateRoute(cmd);

        // then
        assertTrue(route == null, "Route should be null when requestRoute=false");
    }
}
