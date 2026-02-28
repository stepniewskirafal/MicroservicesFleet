package com.galactic.starport.service.routeplanner;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.junit.jupiter.api.Assertions.*;

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

@Execution(ExecutionMode.SAME_THREAD)
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
        assertTrue(route.getEtaLightYears() > 0);
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

    @Test
    @ResourceLock(value = "WIREMOCK", mode = ResourceAccessMode.READ_WRITE)
    void shouldThrowRouteUnavailableExceptionWhen422ReturnedByPlanner() {
        wireMock.resetAll();
        wireMock.stubFor(post(urlEqualTo("/routes/plan"))
                .willReturn(aResponse()
                        .withStatus(422)
                        .withHeader("Content-Type", "application/json")
                        .withBody(
                                """
                                {"error":"ROUTE_REJECTED","reason":"INSUFFICIENT_RANGE","details":"Fuel too low"}
                                """)));

        ReserveBayCommand cmd = ReserveBayCommand.builder()
                .destinationStarportCode("DEST-422")
                .startStarportCode("ORIGIN-422")
                .customerCode("CUST-422")
                .shipCode("SS-422")
                .shipClass(ReserveBayCommand.ShipClass.SCOUT)
                .startAt(Instant.parse("2009-01-01T00:00:00Z"))
                .endAt(Instant.parse("2009-01-01T01:00:00Z"))
                .requestRoute(true)
                .build();

        assertThrows(RouteUnavailableException.class, () -> routePlanner.calculateRoute(cmd));
    }
}
