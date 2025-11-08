package com.galactic.starport.service

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.micrometer.observation.ObservationRegistry
import io.micrometer.core.instrument.observation.DefaultMeterObservationHandler
import spock.lang.Specification

import java.time.Instant

class FeeCalculatorServiceMetricsSpec extends Specification {

    private SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry()
    private ObservationRegistry observationRegistry
    private FeeCalculatorService feeCalculatorService

    def setup() {
        observationRegistry = ObservationRegistry.create()
        observationRegistry.observationConfig().observationHandler(new DefaultMeterObservationHandler(meterRegistry))
        feeCalculatorService = new FeeCalculatorService(meterRegistry, observationRegistry)
        feeCalculatorService.initMetrics()
    }

    def "records fee calculation metrics"() {
        given:
        def reservation = Reservation.builder()
                .ship(Ship.builder().shipClass(Ship.ShipClass.SCOUT).build())
                .startAt(Instant.parse("2025-01-01T10:00:00Z"))
                .endAt(Instant.parse("2025-01-01T12:00:00Z"))
                .build()

        when:
        def fee = feeCalculatorService.calculateFee(reservation)

        then:
        fee == BigDecimal.valueOf(100)
        meterRegistry.get("reservations.fees.calculation").tag("status", "success").timer().count() == 1
        meterRegistry.get("reservations.fees.calculated.amount").summary().count() == 1
        meterRegistry.get("reservations.fees.calculated.amount").summary().totalAmount() == 100.0d
    }
}