package com.galactic.starport.service

import com.galactic.starport.repository.StarportPersistenceFacade
import io.micrometer.observation.tck.TestObservationRegistry
import spock.lang.Specification

import java.time.Instant

import static io.micrometer.observation.tck.TestObservationRegistryAssert.assertThat as assertObservation

class CreateHoldReservationServiceObservabilityTest extends Specification {

    private static final String DEST = "DEF"

    def observationRegistry = TestObservationRegistry.create()
    def persistenceFacade = Mock(StarportPersistenceFacade)
    def service = new CreateHoldReservationService(
            persistenceFacade,
            observationRegistry
    )

    def "allocateHold creates observation and delegates to persistenceFacade"() {
        given:
        def cmd = ReserveBayCommand.builder()
                .destinationStarportCode(DEST)
                .startStarportCode("ALPHA-BASE")
                .customerCode("CUST-001")
                .shipCode("SS-Enterprise-01")
                .shipClass(ReserveBayCommand.ShipClass.SCOUT)
                .startAt(Instant.parse("2001-01-01T00:00:00Z"))
                .endAt(Instant.parse("2001-01-01T01:00:00Z"))
                .requestRoute(true)
                .build()

        when:
        service.createHoldReservation(cmd)

        then: "serwis deleguje do fasady i zwraca jej wynik"
        1 * persistenceFacade.createHoldReservation(cmd)

        and: "obserwacja została zarejestrowana z poprawnymi tagami"
        assertObservation(observationRegistry)
                .hasObservationWithNameEqualTo("reservations.hold.allocate")
                .that()
                .hasBeenStarted()
                .hasBeenStopped()
                .hasLowCardinalityKeyValue("starport", DEST)
                .hasLowCardinalityKeyValue("shipClass", cmd.shipClass().name())
    }
}
