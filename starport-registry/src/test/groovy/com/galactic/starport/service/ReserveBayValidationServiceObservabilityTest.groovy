package com.galactic.starport.service

import io.micrometer.observation.tck.TestObservationRegistry
import org.springframework.validation.Errors
import spock.lang.Specification

import static io.micrometer.observation.tck.TestObservationRegistryAssert.assertThat as assertObservation

class ReserveBayValidationServiceObservabilityTest extends Specification {

    def observationRegistry = TestObservationRegistry.create()
    def rule1 = Mock(ReserveBayCommandValidationRule)
    def rule2 = Mock(ReserveBayCommandValidationRule)
    def composite = new ReserveBayValidationService(
            [rule1, rule2],
            observationRegistry
    )

    def "creates parent observation and child observations per rule with proper tags"() {
        given: "correct command"
        def cmd = ReserveBayCommand.builder()
                .destinationStarportCode("DEST")
                .startStarportCode("START")
                .customerCode("CUST-001")
                .shipCode("SHIP-001")
                .shipClass(ReserveBayCommand.ShipClass.SCOUT)
                .startAt(java.time.Instant.parse("2005-01-01T00:00:00Z"))
                .endAt(java.time.Instant.parse("2005-01-01T01:00:00Z"))
                .requestRoute(true)
                .build()

        when:
        composite.validate(cmd)

        then: "każda reguła została wywołana dokładnie raz"
        1 * rule1.validate(cmd, _ as Errors)
        1 * rule2.validate(cmd, _ as Errors)

        and: "parent observation ma poprawną nazwę, tag i został rozpoczęty/zatrzymany"
        def obsAssert = assertObservation(observationRegistry)
        obsAssert
                .hasObservationWithNameEqualTo("validation.reserve-bay")
                .that()
                .hasLowCardinalityKeyValue("routeRequested", "true")
                .hasBeenStarted()
                .hasBeenStopped()

        and: "dla każdej reguły utworzono observation 'validation.rule' z nazwą reguły"
        obsAssert
                .hasNumberOfObservationsWithNameEqualTo("validation.rule", 2)

        obsAssert
                .hasObservationWithNameEqualTo("validation.rule")
                .that()
                .hasLowCardinalityKeyValue("rule", rule1.getClass().getSimpleName())

        obsAssert
                .hasObservationWithNameEqualTo("validation.rule")
                .that()
                .hasLowCardinalityKeyValue("rule", rule2.getClass().getSimpleName())
    }

    def "marks parent observation as error when rule throws exception and still stops it"() {
        given:
        def cmd = ReserveBayCommand.builder()
                .destinationStarportCode("DEST")
                .startStarportCode("START")
                .customerCode("CUST-001")
                .shipCode("SHIP-001")
                .shipClass(ReserveBayCommand.ShipClass.SCOUT)
                .startAt(java.time.Instant.parse("2005-01-01T00:00:00Z"))
                .endAt(java.time.Instant.parse("2005-01-01T01:00:00Z"))
                .requestRoute(false)
                .build()

        and: "pierwsza reguła rzuca wyjątek"
        def boom = new RuntimeException("boom")
        rule1.validate(cmd, _ as Errors) >> { throw boom }

        when:
        composite.validate(cmd)

        then: "wyjątek propaguje się do testu"
        def thrownEx = thrown(RuntimeException)
        thrownEx.is(boom)

        and: "parent observation ma oznaczony błąd i został zatrzymany"
        assertObservation(observationRegistry)
                .hasObservationWithNameEqualTo("validation.reserve-bay")
                .that()
                .hasError()
                .hasBeenStopped()
    }
}
