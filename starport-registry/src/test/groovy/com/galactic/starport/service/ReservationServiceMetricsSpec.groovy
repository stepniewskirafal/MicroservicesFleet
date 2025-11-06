package com.galactic.starport.service


import com.galactic.starport.repository.StarportEntity
import com.galactic.starport.repository.StarportRepository
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import spock.lang.Specification

import java.math.BigDecimal
import java.time.Instant
import java.util.Optional

class ReservationServiceMetricsSpec extends Specification {

    private HoldReservationService holdReservationService = Mock()
    private ValidateReservationCommandService validateReservationCommandService = Mock()
    private FeeCalculatorService feeCalculatorService = Mock()
    private RoutePlannerService routePlannerService = Mock()
    private StarportRepository starportRepository = Mock()
    private SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry()
    private ReservationService reservationService

    def setup() {
        reservationService = new ReservationService(
                holdReservationService,
                validateReservationCommandService,
                feeCalculatorService,
                routePlannerService,
                starportRepository,
                meterRegistry)
        reservationService.initMetrics()
    }

    def "records success and timing when reservation completes"() {
        given:
        def command = ReserveBayCommand.builder()
                .startStarportCode("START")
                .destinationStarportCode("DEST")
                .customerCode("CUST")
                .shipCode("SHIP")
                .shipClass(ReserveBayCommand.ShipClass.SCOUT)
                .startAt(Instant.now())
                .endAt(Instant.now().plusSeconds(3600))
                .requestRoute(true)
                .build()

        def starport = Mock(StarportEntity)
        def reservation = Reservation.builder()
                .id(1L)
                .ship(Ship.builder().shipClass(Ship.ShipClass.SCOUT).build())
                .startAt(command.startAt())
                .endAt(command.endAt())
                .status(Reservation.ReservationStatus.HOLD)
                .build()

        starportRepository.findByCode(command.destinationStarportCode()) >> Optional.of(starport)
        holdReservationService.allocateHold(command, starport) >> reservation
        feeCalculatorService.calculateFee(reservation) >> BigDecimal.TEN
        routePlannerService.addRoute(command, reservation, starport) >> Optional.of(reservation)

        when:
        reservationService.reserveBay(command)

        then:
        meterRegistry.get("reservations.reserve.success").counter().count() == 1.0d
        meterRegistry.get("reservations.reserve.duration").timer().count() == 1
        meterRegistry.get("reservations.reserve.errors").counter().count() == 0.0d
    }

    def "records error when reservation fails"() {
        given:
        def command = ReserveBayCommand.builder()
                .startStarportCode("START")
                .destinationStarportCode("DEST")
                .customerCode("CUST")
                .shipCode("SHIP")
                .shipClass(ReserveBayCommand.ShipClass.SCOUT)
                .startAt(Instant.now())
                .endAt(Instant.now().plusSeconds(3600))
                .requestRoute(false)
                .build()

        starportRepository.findByCode(command.destinationStarportCode()) >> Optional.empty()

        when:
        reservationService.reserveBay(command)

        then:
        thrown(StarportNotFoundException)
        meterRegistry.get("reservations.reserve.errors").counter().count() == 1.0d
        meterRegistry.get("reservations.reserve.success").counter().count() == 0.0d
        meterRegistry.get("reservations.reserve.duration").timer().count() == 1
    }
}