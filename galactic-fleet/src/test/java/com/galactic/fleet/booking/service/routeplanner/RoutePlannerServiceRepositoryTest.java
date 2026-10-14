package com.galactic.fleet.booking.service.routeplanner;

import static org.junit.jupiter.api.Assertions.*;

import com.galactic.fleet.booking.BaseAcceptanceTest;
import com.galactic.fleet.booking.service.ReserveBayCommand;
import com.galactic.fleet.booking.service.Route;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.api.parallel.ResourceAccessMode;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.springframework.beans.factory.annotation.Autowired;

@Execution(ExecutionMode.SAME_THREAD)
// Through the Spring context: the adapter calls the REAL routeplanning module (no stub). Failure
// translation (rejection → 422 path, publish failure → 409 path) is covered by RoutePlanningModuleAdapterTest.
@ResourceLock(value = "DB_TRUNCATE", mode = ResourceAccessMode.READ)
class RoutePlannerServiceRepositoryTest extends BaseAcceptanceTest {

    @Autowired
    RoutePlanner routePlanner;

    @Test
    void shouldCalculateRouteWhenRequested() {
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

        Route route = routePlanner.calculateRoute(cmd);

        assertNotNull(route);
        assertTrue(route.getRouteCode().startsWith("ROUTE-"), "Route code should start with ROUTE-");
        assertEquals(cmd.startStarportCode(), route.getStartStarportCode());
        assertEquals(cmd.destinationStarportCode(), route.getDestinationStarportCode());
        assertTrue(route.getEtaHours() > 0);
        assertTrue(route.getRiskScore() >= 0 && route.getRiskScore() <= 1.0);
        assertTrue(route.isActive());
    }

    @Test
    void shouldReturnNullRouteWhenNotRequested() {
        ReserveBayCommand cmd = ReserveBayCommand.builder()
                .destinationStarportCode("DEF-NO-ROUTE")
                .startStarportCode("ALPHA-BASE-NO-ROUTE")
                .customerCode("CUST-NO-ROUTE")
                .shipCode("SS-Enterprise-NO-ROUTE")
                .shipClass(ReserveBayCommand.ShipClass.SCOUT)
                .startAt(Instant.parse("2008-01-01T00:00:00Z"))
                .endAt(Instant.parse("2008-01-01T01:00:00Z"))
                .requestRoute(false)
                .build();

        Route route = routePlanner.calculateRoute(cmd);

        assertNull(route, "Route should be null when requestRoute=false");
    }
}
