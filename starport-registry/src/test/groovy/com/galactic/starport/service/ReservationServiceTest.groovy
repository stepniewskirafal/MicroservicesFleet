package com.galactic.starport.service

import com.galactic.starport.BaseAcceptanceSpec
import com.galactic.starport.repository.DockingBayEntity
import com.galactic.starport.repository.DockingBayRepository
import com.galactic.starport.repository.StarportEntity
import com.galactic.starport.repository.StarportRepository
import spock.lang.Subject

import java.time.Instant

class ReservationServiceTest extends BaseAcceptanceSpec {

        HoldReservationService holdReservationService = Mock()
        ValidateReservationCommandService validateReservationCommandService = Mock()
        FeeCalculatorService feeCalculatorService = Mock()
        DockingBayRepository dockingBayRepository = Mock()
        RoutePlannerService routePlannerService = Mock()
        StarportRepository starportRepository = Mock()

        @Subject
        ReservationService service = new ReservationService(
                holdReservationService,
                validateReservationCommandService,
                feeCalculatorService,
                dockingBayRepository,
                routePlannerService,
                starportRepository
        )

        def "returns reservation when planner keeps hold"() {
            given:
            def command = sampleCommand(false)
            def holdReservation = Reservation.builder()
                    .id(1L)
                    .status(Reservation.ReservationStatus.HOLD)
                    .build()
            mockCommonDependencies(command, holdReservation)
            routePlannerService.addRoute(command, holdReservation, _ as StarportEntity) >> Optional.of(holdReservation)

            when:
            def result = service.reserveBay(command)

            then:
            result.isPresent()
            result.get().is(holdReservation)
        }

        def "returns confirmed reservation when planner succeeds"() {
            given:
            def command = sampleCommand(true)
            def holdReservation = Reservation.builder()
                    .id(2L)
                    .status(Reservation.ReservationStatus.HOLD)
                    .build()
            def confirmedReservation = Reservation.builder()
                    .id(2L)
                    .status(Reservation.ReservationStatus.CONFIRMED)
                    .build()
            mockCommonDependencies(command, holdReservation)
            routePlannerService.addRoute(command, holdReservation, _ as StarportEntity) >> Optional.of(confirmedReservation)

            when:
            def result = service.reserveBay(command)

            then:
            result.isPresent()
            result.get().is(confirmedReservation)
        }

        def "propagates empty optional when planner releases hold"() {
            given:
            def command = sampleCommand(true)
            def holdReservation = Reservation.builder()
                    .id(3L)
                    .status(Reservation.ReservationStatus.HOLD)
                    .build()
            mockCommonDependencies(command, holdReservation)
            routePlannerService.addRoute(command, holdReservation, _ as StarportEntity) >> Optional.empty()

            when:
            def result = service.reserveBay(command)

            then:
            !result.isPresent()
        }

        private void mockCommonDependencies(ReserveBayCommand command, Reservation holdReservation) {
            validateReservationCommandService.validate(command) >> {}
            def starport = Mock(StarportEntity)
            starportRepository.findByCode(command.startStarportCode()) >> Optional.of(starport)
            dockingBayRepository.findFreeBay(
                    command.destinationStarportCode(),
                    command.shipClass().name(),
                    command.startAt(),
                    command.endAt()
            ) >> Optional.of(Mock(DockingBayEntity))
            holdReservationService.allocateHold(command, _ as DockingBayEntity, starport) >> holdReservation
            feeCalculatorService.calculateFee(holdReservation) >> BigDecimal.TEN
        }

        private static ReserveBayCommand sampleCommand(boolean requestRoute) {
            def start = Instant.parse("2025-01-01T00:00:00Z")
            ReserveBayCommand.builder()
                    .startStarportCode("START")
                    .destinationStarportCode("DEST")
                    .customerCode("CUST")
                    .shipCode("SHIP")
                    .shipClass(ReserveBayCommand.ShipClass.SCOUT)
                    .startAt(start)
                    .endAt(start.plusSeconds(3600))
                    .requestRoute(requestRoute)
                    .build()
        }
    }