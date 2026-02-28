package com.galactic.starport.service.routeplanner;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.galactic.starport.service.ReserveBayCommand;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.tck.TestObservationRegistry;
import io.micrometer.observation.tck.TestObservationRegistryAssert;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

@Execution(ExecutionMode.CONCURRENT)
class RoutePlannerServiceObservabilityTest {

    private static final String ORIGIN = "ALPHA-BASE-ROUTE-OBS";
    private static final String DESTINATION = "DEF-ROUTE-OBS";

    private TestObservationRegistry observationRegistry;
    private SimpleMeterRegistry meterRegistry;
    private RoutePlannerService service;

    @BeforeEach
    void setUp() {
        observationRegistry = TestObservationRegistry.create();
        meterRegistry = new SimpleMeterRegistry();
        service = new RoutePlannerService(meterRegistry, observationRegistry);
    }

    @Test
    void calculateRouteRegistersObservationsAndMetrics() {
        // given - komenda rezerwacji z włączonym wyznaczaniem trasy
        ReserveBayCommand cmd = ReserveBayCommand.builder()
                .destinationStarportCode(DESTINATION)
                .startStarportCode(ORIGIN)
                .customerCode("CUST-ROUTE-OBS")
                .shipCode("SS-Enterprise-ROUTE-OBS")
                .shipClass(ReserveBayCommand.ShipClass.SCOUT)
                .startAt(Instant.parse("2007-01-01T00:00:00Z"))
                .endAt(Instant.parse("2007-01-01T01:00:00Z"))
                .requestRoute(true)
                .build();

        // when - wyznaczamy trasę
        service.calculateRoute(cmd);

        // then - obserwacja planowania trasy jest zarejestrowana z poprawnymi tagami
        TestObservationRegistryAssert.assertThat(observationRegistry)
                .hasObservationWithNameEqualTo("reservations.route.plan")
                .that()
                .hasBeenStarted()
                .hasBeenStopped()
                .hasLowCardinalityKeyValue("startStarport", ORIGIN)
                .hasLowCardinalityKeyValue("destinationStarport", DESTINATION);

        // and - obserwacja wyliczania ryzyka jest zarejestrowana z poprawnymi tagami
        TestObservationRegistryAssert.assertThat(observationRegistry)
                .hasObservationWithNameEqualTo("reservations.route.risk.calculate")
                .that()
                .hasBeenStarted()
                .hasBeenStopped()
                .hasLowCardinalityKeyValue("startStarport", ORIGIN)
                .hasLowCardinalityKeyValue("destinationStarport", DESTINATION);

        // and - obserwacja wyliczania ETA jest zarejestrowana z poprawnymi tagami
        TestObservationRegistryAssert.assertThat(observationRegistry)
                .hasObservationWithNameEqualTo("reservations.route.eta.calculate")
                .that()
                .hasBeenStarted()
                .hasBeenStopped()
                .hasLowCardinalityKeyValue("startStarport", ORIGIN)
                .hasLowCardinalityKeyValue("destinationStarport", DESTINATION);

        // and - metryki sukcesów i błędów planowania trasy są poprawnie zaktualizowane
        Counter successCounter = meterRegistry.get("reservations.route.plan.success").counter();
        Counter errorCounter = meterRegistry.get("reservations.route.plan.errors").counter();

        assertEquals(1d, successCounter.count(), 0.001);
        assertEquals(0d, errorCounter.count(), 0.001);
    }
}
