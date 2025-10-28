package com.galactic.starport.service

import com.galactic.starport.BaseAcceptanceSpec
import com.galactic.starport.domain.Reservation
import com.galactic.starport.domain.ReserveBayCommand
import com.galactic.starport.domain.Route
import com.galactic.starport.repository.ReservationPersistenceAdapter
import spock.lang.Subject

class RoutePlannerServiceTest extends BaseAcceptanceSpec {

    ReservationPersistenceAdapter reservationPersistenceAdapter = Mock()

    @Subject
    RoutePlannerService service = Spy(new RoutePlannerService(reservationPersistenceAdapter))

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
        def result = service.addRoute(command, reservation)

        then:
        result.isPresent()
        result.get().is(reservation)
        0 * service.planRoute(_)
        0 * reservationPersistenceAdapter.save(_)
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
        1 * reservationPersistenceAdapter.save(_ as Reservation) >> { Reservation confirmed -> confirmed }

        when:
        def result = service.addRoute(command, reservation)

        then:
        result.isPresent()
        def confirmed = result.get()
        confirmed.status == Reservation.ReservationStatus.CONFIRMED
        confirmed.feeCharged.compareTo(BigDecimal.valueOf(20.0d)) == 0
        confirmed.routes == [route]
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

        and:
        1 * service.planRoute(command) >> { throw new IllegalStateException("boom") }
        1 * reservationPersistenceAdapter.cancelReservation(42L)

        when:
        def result = service.addRoute(command, reservation)

        then:
        !result.isPresent()
    }
}
