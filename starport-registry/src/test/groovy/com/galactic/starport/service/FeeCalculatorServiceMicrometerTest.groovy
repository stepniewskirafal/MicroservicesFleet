package com.galactic.starport.service

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.micrometer.observation.tck.TestObservationRegistry
import spock.lang.Specification

import java.time.Instant

import static io.micrometer.observation.tck.TestObservationRegistryAssert.assertThat as assertObservation
import static io.micrometer.core.tck.MeterRegistryAssert.assertThat as assertMeterRegistry

class FeeCalculatorServiceMicrometerTest extends Specification {

    private static final String DEST = "DEF"

    MeterRegistry meterRegistry = new SimpleMeterRegistry()
    def observationRegistry = TestObservationRegistry.create()
    def feeCalculatorService = new FeeCalculatorService(meterRegistry, observationRegistry)

    def "calculateFee emits observation with expected low-cardinality tags"() {
        given:
        def start = Instant.parse("2000-01-01T00:00:00Z")
        def cmd = ReserveBayCommand.builder()
                .destinationStarportCode(DEST)
                .startStarportCode("ALPHA-BASE")
                .customerCode("CUST-001")
                .shipCode("SS-Enterprise-01")
                .shipClass(ReserveBayCommand.ShipClass.SCOUT)
                .startAt(start)
                .endAt(start.plusSeconds(1 * 3600L))   // 1 godzina
                .requestRoute(true)
                .build()

        when:
        def fee = feeCalculatorService.calculateFee(cmd)

        then: "powstała obserwacja z odpowiednimi tagami"
        assertObservation(observationRegistry)
                .hasObservationWithNameEqualTo("reservations.fees.calculate")
                .that()
                .hasBeenStarted()
                .hasBeenStopped()
                .hasLowCardinalityKeyValue("starport", DEST)
                .hasLowCardinalityKeyValue("shipClass", "SCOUT")

        and: "zwrócona opłata jest dodatnia (biznesowo sensowna)"
        fee > 0
    }

    def "calculateFee records fee and hours distribution summaries"() {
        given:
        def start = Instant.parse("2000-01-01T00:00:00Z")
        def hours = 2L
        def cmd = ReserveBayCommand.builder()
                .destinationStarportCode(DEST)
                .startStarportCode("ALPHA-BASE")
                .customerCode("CUST-001")
                .shipCode("SS-Enterprise-01")
                .shipClass(ReserveBayCommand.ShipClass.FREIGHTER)
                .startAt(start)
                .endAt(start.plusSeconds(hours * 3600L))
                .requestRoute(true)
                .build()

        when:
        def fee = feeCalculatorService.calculateFee(cmd)

        then: "metryki zostały zarejestrowane w MeterRegistry (fluent API z MeterRegistryAssert)"
        assertMeterRegistry(meterRegistry)
                .hasMeterWithName("reservations.fees.calculated.amount")
                .hasMeterWithName("reservations.fees.calculated.hours")

        and: "podsumowanie kwoty opłaty jest spójne z wynikiem biznesowym"
        def feeSummary = meterRegistry.get("reservations.fees.calculated.amount").summary()
        feeSummary.count() == 1
        // DistributionSummary zapisuje double – porównujemy z fee.doubleValue()
        feeSummary.totalAmount() == fee.doubleValue()
        feeSummary.id.baseUnit == "pln"
        feeSummary.id.description == "Calculated reservation fee amount"

        and: "podsumowanie liczby godzin jest spójne z czasem naliczania"
        def hoursSummary = meterRegistry.get("reservations.fees.calculated.hours").summary()
        hoursSummary.count() == 1
        hoursSummary.totalAmount() == (double) hours
        hoursSummary.id.baseUnit == "hours"
        hoursSummary.id.description == "Charged hours used to calculate reservation fee"
    }
}
