package com.galactic.starport.service

import com.galactic.starport.repository.ReservationEntity
import com.galactic.starport.repository.ReservationRepository
import com.galactic.starport.repository.StarportEntity
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.micrometer.observation.ObservationRegistry
import spock.lang.Specification

import java.math.BigDecimal
import java.time.Instant
import java.util.Optional

class RoutePlannerServiceMetricsSpec extends Specification {

    private ReservationRepository reservationRepository = Mock()
    private OutboxWriter outboxWriter = Mock()
    private SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry()
    private ObservationRegistry observationRegistry = ObservationRegistry.create()

    private RoutePlannerService routePlannerService

    def setup() {
        routePlannerService = new RoutePlannerService(
                reservationRepository,
                outboxWriter,
                observationRegistry,
                meterRegistry
        )
        routePlannerService.initMetrics()
        routePlannerService.@reservationsBinding = "reservations-out"
    }

    // ---- helpers: bez Optional ----
    private long timerCount(String name) {
        def t = meterRegistry.find(name).timer()
        return t != null ? t.count() : 0L
    }

    private double counterCount(String name) {
        def c = meterRegistry.find(name).counter()
        return c != null ? c.count() : 0D
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
        outboxWriter.append(*_) >> { /* ok */ }

        when:
        def result = routePlannerService.addRoute(command, reservation, starportEntity)

        then:
        result.isPresent()

        // confirmation
        timerCount("reservations.route.confirmation.duration") == 1
        counterCount("reservations.route.confirmation.success") == 1
        counterCount("reservations.route.confirmation.errors")  == 0

        // planning
        timerCount("reservations.route.planning.duration") == 1
        counterCount("reservations.route.planning.success") == 1
        counterCount("reservations.route.planning.errors")  == 0
    }

    def "records confirmation errors and releases hold"() {
        given:
        def command = ReserveBayCommand.builder()
                .startStarportCode("START")
                .destinationStarportCode("DEST")
                .requestRoute(false) // bez planowania – od razu confirm + save
                .build()

        def reservation = Reservation.builder()
                .id(99L)
                .feeCharged(BigDecimal.valueOf(200))
                .startAt(Instant.parse("2025-01-01T10:00:00Z"))
                .endAt(Instant.parse("2025-01-01T12:00:00Z"))
                .ship(Ship.builder().shipClass(Ship.ShipClass.SCOUT).build())
                .status(Reservation.ReservationStatus.HOLD)
                .build()

        def persisted = Mock(ReservationEntity)

        when:
        def result = routePlannerService.addRoute(command, reservation, new StarportEntity())

        then:
        !result.isPresent()

        // 1) Ten konkretny save MUSI rzucić wyjątek -> wymusza przejście przez catch + releaseHold
        1 * reservationRepository.save(_ as ReservationEntity) >> { throw new RuntimeException("db down") }

        // 2) releaseHold sprawdza istniejący rekord i wysyła event "ReservationReleased"
        1 * reservationRepository.findById(99L) >> Optional.of(persisted)
        1 * outboxWriter.append("reservations-out", "ReservationReleased", _ as String, _ as Map, _ as Map)

        // 3) Na pewno NIE było potwierdzenia
        0 * outboxWriter.append("reservations-out", "ReservationConfirmed", _, _, _)
        0 * _

        and:
        timerCount("reservations.route.confirmation.duration") == 1
        counterCount("reservations.route.confirmation.errors")  == 1
        counterCount("reservations.route.confirmation.success") == 0
    }


    def "records plan route metrics only"() {
        given:
        def command = ReserveBayCommand.builder()
                .startStarportCode("START")
                .destinationStarportCode("DEST")
                .requestRoute(true)
                .build()

        when:
        def route = routePlannerService.planRoute(command)

        then:
        route != null
        timerCount("reservations.route.planning.duration") == 1
        counterCount("reservations.route.planning.success") == 1
        counterCount("reservations.route.planning.errors")  == 0
    }
}
