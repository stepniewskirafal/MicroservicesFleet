package com.galactic.starport.service


import com.galactic.starport.repository.ReservationEntity
import com.galactic.starport.repository.ReservationRepository
import com.galactic.starport.repository.StarportEntity
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import spock.lang.Specification

import java.math.BigDecimal
import java.time.Instant
import java.util.Optional

class RoutePlannerServiceMetricsSpec extends Specification {

    private ReservationRepository reservationRepository = Mock()
    private OutboxWriter outboxWriter = Mock()
    private SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry()
    private RoutePlannerService routePlannerService

    def setup() {
        routePlannerService = new RoutePlannerService(reservationRepository, outboxWriter, meterRegistry)
        routePlannerService.initMetrics()
        routePlannerService.@reservationsBinding = "reservations-out"
    }

    def "records successful route planning and confirmation"() {
        given:
        def command = ReserveBayCommand.builder()
                .startStarportCode("START")
                .destinationStarportCode("DEST")
                .requestRoute(true)
                .build()

        def reservation = Reservation.builder()
                .id(1L)
                .feeCharged(BigDecimal.valueOf(150))
                .startAt(Instant.parse("2025-01-01T10:00:00Z"))
                .endAt(Instant.parse("2025-01-01T12:00:00Z"))
                .ship(Ship.builder().shipClass(Ship.ShipClass.SCOUT).build())
                .status(Reservation.ReservationStatus.HOLD)
                .build()

        def starportEntity = new StarportEntity()
        def persisted = Mock(ReservationEntity)

        reservationRepository.save(_ as ReservationEntity) >> persisted
        outboxWriter.append(*_) >> { }

        when:
        def result = routePlannerService.addRoute(command, reservation, starportEntity)

        then:
        result.isPresent()
        meterRegistry.get("reservations.route.confirmation.success").counter().count() == 1.0d
        meterRegistry.get("reservations.route.confirmation.errors").counter().count() == 0.0d
        meterRegistry.get("reservations.route.confirmation.duration").timer().count() == 1
        meterRegistry.get("reservations.route.planning.success").counter().count() == 1.0d
        meterRegistry.get("reservations.route.planning.errors").counter().count() == 0.0d
        meterRegistry.get("reservations.route.planning.duration").timer().count() == 1
    }

    def "records confirmation errors and releases hold"() {
        given:
        def command = ReserveBayCommand.builder()
                .startStarportCode("START")
                .destinationStarportCode("DEST")
                .requestRoute(false)
                .build()

        def reservation = Reservation.builder()
                .id(99L)
                .feeCharged(BigDecimal.valueOf(200))
                .startAt(Instant.parse("2025-01-01T10:00:00Z"))
                .endAt(Instant.parse("2025-01-01T12:00:00Z"))
                .ship(Ship.builder().shipClass(Ship.ShipClass.SCOUT).build())
                .status(Reservation.ReservationStatus.HOLD)
                .build()

        def starportEntity = new StarportEntity()
        def persisted = Mock(ReservationEntity)

        reservationRepository.save(_ as ReservationEntity) >> { throw new RuntimeException("db down") } >> persisted
        reservationRepository.findById(reservation.id) >> Optional.of(persisted)

        when:
        def result = routePlannerService.addRoute(command, reservation, starportEntity)

        then:
        !result.isPresent()
        meterRegistry.get("reservations.route.confirmation.errors").counter().count() == 1.0d
        meterRegistry.get("reservations.route.confirmation.success").counter().count() == 0.0d
        meterRegistry.get("reservations.route.confirmation.duration").timer().count() == 1
    }

    def "records plan route metrics"() {
        given:
        def command = ReserveBayCommand.builder()
                .startStarportCode("START")
                .destinationStarportCode("DEST")
                .requestRoute(true)
                .build()

        when:
        routePlannerService.planRoute(command)

        then:
        meterRegistry.get("reservations.route.planning.duration").timer().count() == 1
        meterRegistry.get("reservations.route.planning.success").counter().count() == 1.0d
        meterRegistry.get("reservations.route.planning.errors").counter().count() == 0.0d
    }
}