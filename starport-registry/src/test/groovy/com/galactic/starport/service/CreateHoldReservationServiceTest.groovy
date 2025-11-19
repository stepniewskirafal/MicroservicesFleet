package com.galactic.starport.service

import com.galactic.starport.BaseAcceptanceSpec
import com.galactic.starport.repository.StarportPersistenceFacade
import org.springframework.beans.factory.annotation.Autowired
import java.time.Instant

class CreateHoldReservationServiceTest extends BaseAcceptanceSpec {
    @Autowired
    CreateHoldReservationService createHoldReservationService
    @Autowired
    StarportPersistenceFacade starportPersistenceFacade

    private static final String DEST = "DEF"

    def setup() {
        purgeAndReset()
        seedDefaultReservationFixture(DEST, [destinationName: "Alpha Base Central"])
    }

    def "should allocate reservation"() {
        given:
        def cmd = ReserveBayCommand.builder()
                .destinationStarportCode(DEST)
                .startStarportCode("ALPHA-BASE")
                .customerCode("CUST-001")
                .shipCode("SS-Enterprise-01")
                .shipClass(ReserveBayCommand.ShipClass.SCOUT)
                .startAt(Instant.parse("2000-01-01T00:00:00Z"))
                .endAt(Instant.parse("2000-01-01T01:00:00Z"))
                .requestRoute(true)
                .build()
        when:
        def reservationID = createHoldReservationService.allocateHold(cmd)
        then:
        starportPersistenceFacade.reservationExistsById(reservationID)
    }

    def "allocateHold – rzuca StarportNotFoundException gdy starport nie istnieje"() {
        given:
        def cmd = ReserveBayCommand.builder()
                .destinationStarportCode(DEST+"-NOEXIST")
                .startStarportCode("ALPHA-BASE")
                .customerCode("CUST-001")
                .shipCode("SS-Enterprise-01")
                .shipClass(ReserveBayCommand.ShipClass.SCOUT)
                .startAt(Instant.parse("2000-01-01T00:00:00Z"))
                .endAt(Instant.parse("2000-01-01T01:00:00Z"))
                .requestRoute(true)
                .build()
        when:
        createHoldReservationService.allocateHold(cmd)
        then:
        thrown(StarportNotFoundException)
    }

    def "allocateHold – rzuca CustomerNotFoundException gdy customer nie istnieje"() {
        given:
        def cmd = ReserveBayCommand.builder()
                .destinationStarportCode(DEST)
                .startStarportCode("ALPHA-BASE")
                .customerCode("CUST-001"+"-NOEXIST")
                .shipCode("SS-Enterprise-01")
                .shipClass(ReserveBayCommand.ShipClass.SCOUT)
                .startAt(Instant.parse("2000-01-01T00:00:00Z"))
                .endAt(Instant.parse("2000-01-01T01:00:00Z"))
                .requestRoute(true)
                .build()
        when:
        createHoldReservationService.allocateHold(cmd)
        then:
        thrown(CustomerNotFoundException)
    }

    def "allocateHold – rzuca ShipNotFoundException gdy ship nie istnieje"() {
        given:
        def cmd = ReserveBayCommand.builder()
                .destinationStarportCode(DEST)
                .startStarportCode("ALPHA-BASE")
                .customerCode("CUST-001")
                .shipCode("SS-Enterprise-01"+"-NOEXIST")
                .shipClass(ReserveBayCommand.ShipClass.SCOUT)
                .startAt(Instant.parse("2000-01-01T00:00:00Z"))
                .endAt(Instant.parse("2000-01-01T01:00:00Z"))
                .requestRoute(true)
                .build()
        when:
        createHoldReservationService.allocateHold(cmd)
        then:
        thrown(ShipNotFoundException)
    }

    def "allocateHold – rzuca NoDockingBaysAvailableException gdy nie ma dostępnego miejsca na podaną godzinę"() {
        given:
        def cmd = ReserveBayCommand.builder()
                .destinationStarportCode(DEST)
                .startStarportCode("ALPHA-BASE")
                .customerCode("CUST-001")
                .shipCode("SS-Enterprise-01")
                .shipClass(ReserveBayCommand.ShipClass.SCOUT)
                .startAt(Instant.parse("2000-01-01T00:00:00Z"))
                .endAt(Instant.parse("2000-01-01T01:00:00Z"))
                .requestRoute(true)
                .build()
        when:
        createHoldReservationService.allocateHold(cmd)
        createHoldReservationService.allocateHold(cmd)
        then:
        thrown(NoDockingBaysAvailableException)
    }
}
