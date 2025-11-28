package com.galactic.starport.service

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.micrometer.observation.tck.TestObservationRegistry
import spock.lang.Specification

import java.time.Instant

import static io.micrometer.observation.tck.TestObservationRegistryAssert.assertThat

class RoutePlannerServiceObservabilityTest extends Specification {

    private static final String ORIGIN = "ALPHA-BASE-ROUTE-OBS"
    private static final String DESTINATION = "DEF-ROUTE-OBS"

    def observationRegistry = TestObservationRegistry.create()
    def meterRegistry = new SimpleMeterRegistry()

    def service = new RoutePlannerService(
            meterRegistry,
            observationRegistry
    )

    def "calculateRoute registers observations and metrics for route planning"() {
        given: "komenda rezerwacji z włączonym wyznaczaniem trasy"
        def cmd = ReserveBayCommand.builder()
                .destinationStarportCode(DESTINATION)
                .startStarportCode(ORIGIN)
                .customerCode("CUST-ROUTE-OBS")
                .shipCode("SS-Enterprise-ROUTE-OBS")
                .shipClass(ReserveBayCommand.ShipClass.SCOUT)
                .startAt(Instant.parse("2007-01-01T00:00:00Z"))
                .endAt(Instant.parse("2007-01-01T01:00:00Z"))
                .requestRoute(true)
                .build()

        when: "wyznaczamy trasę"
        service.calculateRoute(cmd)

        then: "obserwacja planowania trasy jest zarejestrowana z poprawnymi tagami"
        assertThat(observationRegistry)
                .hasObservationWithNameEqualTo("reservations.route.plan")
                .that()
                .hasBeenStarted()
                .hasBeenStopped()
                .hasLowCardinalityKeyValue("startStarport", ORIGIN)
                .hasLowCardinalityKeyValue("destinationStarport", DESTINATION)

        and: "obserwacja wyliczania ryzyka jest zarejestrowana z poprawnymi tagami"
        assertThat(observationRegistry)
                .hasObservationWithNameEqualTo("reservations.route.risk.calculate")
                .that()
                .hasBeenStarted()
                .hasBeenStopped()
                .hasLowCardinalityKeyValue("startStarport", ORIGIN)
                .hasLowCardinalityKeyValue("destinationStarport", DESTINATION)

        and: "obserwacja wyliczania ETA jest zarejestrowana z poprawnymi tagami"
        assertThat(observationRegistry)
                .hasObservationWithNameEqualTo("reservations.route.eta.calculate")
                .that()
                .hasBeenStarted()
                .hasBeenStopped()
                .hasLowCardinalityKeyValue("startStarport", ORIGIN)
                .hasLowCardinalityKeyValue("destinationStarport", DESTINATION)

        and: "metryki sukcesów i błędów planowania trasy są poprawnie zaktualizowane"
        def successCounter = meterRegistry.get("reservations.route.plan.success").counter()
        def errorCounter = meterRegistry.get("reservations.route.plan.errors").counter()

        successCounter.count() == 1d
        errorCounter.count() == 0d
    }
}
