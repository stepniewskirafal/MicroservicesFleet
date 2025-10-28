package com.galactic.starport.service

import com.galactic.starport.BaseAcceptanceSpec
import com.galactic.starport.repository.ReservationEntity
import com.galactic.starport.repository.ReservationRepository
import com.galactic.starport.repository.StarportEntity
import spock.lang.Subject

class RoutePlannerServiceTest extends BaseAcceptanceSpec {

    ReservationRepository reservationRepository = Mock()

    @Subject
    RoutePlannerService service = Spy(new RoutePlannerService(reservationRepository))

    def "returns original reservation when route is not requested"() {
        given:
        def reservation = Reservation.builder()
                .id(1L)
                .feeCharged(BigDecimal.TEN)
                .status(Reservation.ReservationStatus.HOLD)
                .build()
        def command = ReserveBayCommand.builder()
                .startStarportCode("START")
                .destinationStarportCode("DEST")
                .requestRoute(false)
                .build()

        when:
        def result = service.addRoute(command, reservation, Mock(StarportEntity))

        then:
        result.isPresent()
        result.get().is(reservation)
        0 * service.planRoute(_)
        0 * reservationRepository.save(_)
    }

    def "confirms reservation and persists when route is planned successfully"() {
        given:
        def reservation = Reservation.builder()
                .id(2L)
                .feeCharged(BigDecimal.TEN)
                .status(Reservation.ReservationStatus.HOLD)
                .build()
        def command = ReserveBayCommand.builder()
                .startStarportCode("START")
                .destinationStarportCode("DEST")
                .requestRoute(true)
                .build()
        def route = Route.builder()
                .routeCode("ROUTE-START-DEST-123456")
                .startStarportCode("START")
                .destinationStarportCode("DEST")
                .riskScore(2.0d)
                .etaLightYears(1.5d)
                .isActive(true)
                .build()

        and:
        1 * service.planRoute(command) >> route

        when:
        def result = service.addRoute(command, reservation, Mock(StarportEntity))

        then:
        result.isPresent()
        def confirmed = result.get()
        confirmed.status == Reservation.ReservationStatus.CONFIRMED
        confirmed.feeCharged.compareTo(BigDecimal.valueOf(20.0d)) == 0
        confirmed.routes == [route]
        1 * reservationRepository.save(_ as ReservationEntity)
    }

    def "releases hold when route planning fails"() {
        given:
        def reservation = Reservation.builder()
                .id(42L)
                .feeCharged(BigDecimal.TEN)
                .status(Reservation.ReservationStatus.HOLD)
                .build()
        def command = ReserveBayCommand.builder()
                .startStarportCode("START")
                .destinationStarportCode("DEST")
                .requestRoute(true)
                .build()
        def persistedHold = new ReservationEntity(reservation, null)

        and:
        reservationRepository.findById(42L) >> Optional.of(persistedHold)
        1 * service.planRoute(command) >> { throw new IllegalStateException("boom") }

        when:
        def result = service.addRoute(command, reservation, Mock(StarportEntity))

        then:
        !result.isPresent()
        persistedHold.status == ReservationEntity.ReservationStatus.CANCELLED
        1 * reservationRepository.save(persistedHold)
    }
}