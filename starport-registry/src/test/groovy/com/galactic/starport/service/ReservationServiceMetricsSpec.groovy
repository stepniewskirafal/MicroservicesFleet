package com.galactic.starport.service

import com.galactic.starport.repository.StarportEntity
import com.galactic.starport.repository.StarportRepository
import io.micrometer.core.instrument.observation.DefaultMeterObservationHandler
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.micrometer.observation.ObservationRegistry
import spock.lang.Specification

import java.time.Instant

class ReservationServiceMetricsSpec extends Specification {

    private CreateHoldReservationService holdReservationService = Mock()
    private ReserveBayValidationComposite validateReservationCommandService = Mock()
    private FeeCalculatorService feeCalculatorService = Mock()
    private RoutePlannerService routePlannerService = Mock()
    private StarportRepository starportRepository = Mock()

    private SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry()
    private ObservationRegistry observationRegistry
    private ReservationService reservationService

    def setup() {
        observationRegistry = ObservationRegistry.create()
        observationRegistry.observationConfig()
                .observationHandler(new DefaultMeterObservationHandler(meterRegistry))

        reservationService = new ReservationService(
                holdReservationService,
                validateReservationCommandService,
                feeCalculatorService,
                routePlannerService,
                starportRepository,
                observationRegistry
        )
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
        feeCalculatorService.calculateFee(reservation) >> 10G
        routePlannerService.calculateRoute(command, reservation, starport) >> Optional.of(reservation)

        when:
        reservationService.reserveBay(command)

        then:
        // obecny timer sukcesu
        meterRegistry.get("reservations.reserve").tag("status", "success").timer().count() == 1
        // brak timera błędu – używamy find(...)
        meterRegistry.find("reservations.reserve").tag("status", "error").timer() == null
        // (opcjonalnie) sprawdź tag error=none przy sukcesie
        meterRegistry.get("reservations.reserve")
                .tags("status", "success", "error", "none")
                .timer().count() == 1
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
        // obecny timer błędu
        meterRegistry.get("reservations.reserve").tag("status", "error").timer().count() == 1
        // brak timera sukcesu – używamy find(...)
        meterRegistry.find("reservations.reserve").tag("status", "success").timer() == null
        // (opcjonalnie) klasa wyjątku w tagu error
        meterRegistry.get("reservations.reserve")
                .tags("status", "error", "error", "StarportNotFoundException")
                .timer().count() == 1
    }
}
