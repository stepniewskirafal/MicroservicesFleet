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

    def "should allocate reservation"() {
        given:
        def originCode = "ALPHA-BASE-ALLOCATE"
        def destinationCode = "DEF-ALLOCATE"
        def customerCode = "CUST-ALLOCATE"
        def shipCode = "SS-Enterprise-ALLOCATE"

        seedDefaultReservationFixture(destinationCode, [
                originCode      : originCode,
                customerCode    : customerCode,
                shipCode        : shipCode,
                destinationName : "Alpha Base Central"
        ])

        def cmd = ReserveBayCommand.builder()
                .destinationStarportCode(destinationCode)
                .startStarportCode(originCode)
                .customerCode(customerCode)
                .shipCode(shipCode)
                .shipClass(ReserveBayCommand.ShipClass.SCOUT)
                .startAt(Instant.parse("2002-01-01T00:00:00Z"))
                .endAt(Instant.parse("2002-01-01T01:00:00Z"))
                .requestRoute(true)
                .build()
        when:
        def reservationID = createHoldReservationService.createHoldReservation(cmd)
        then:
        starportPersistenceFacade.reservationExistsById(reservationID)
    }

    def "allocateHold – rzuca StarportNotFoundException gdy starport nie istnieje"() {
        given:
        def originCode = "ALPHA-BASE-STARPORT-NF"
        def existingDestinationCode = "DEF-STARPORT-NF-EXISTS"
        def missingDestinationCode = "DEF-STARPORT-NF-MISSING"
        def customerCode = "CUST-STARPORT-NF"
        def shipCode = "SS-Enterprise-STARPORT-NF"

        seedDefaultReservationFixture(existingDestinationCode, [
                originCode  : originCode,
                customerCode: customerCode,
                shipCode    : shipCode
        ])

        def cmd = ReserveBayCommand.builder()
                .destinationStarportCode(missingDestinationCode)
                .startStarportCode(originCode)
                .customerCode(customerCode)
                .shipCode(shipCode)
                .shipClass(ReserveBayCommand.ShipClass.SCOUT)
                .startAt(Instant.parse("2002-01-01T00:00:00Z"))
                .endAt(Instant.parse("2002-01-01T01:00:00Z"))
                .requestRoute(true)
                .build()
        when:
        createHoldReservationService.createHoldReservation(cmd)
        then:
        thrown(StarportNotFoundException)
    }

    def "allocateHold – rzuca CustomerNotFoundException gdy customer nie istnieje"() {
        given:
        def originCode = "ALPHA-BASE-CUSTOMER-NF"
        def destinationCode = "DEF-CUSTOMER-NF"
        def existingCustomerCode = "CUST-CUSTOMER-NF-EXISTS"
        def missingCustomerCode = "CUST-CUSTOMER-NF-MISSING"
        def shipCode = "SS-Enterprise-CUSTOMER-NF"

        seedDefaultReservationFixture(destinationCode, [
                originCode  : originCode,
                customerCode: existingCustomerCode,
                shipCode    : shipCode
        ])

        def cmd = ReserveBayCommand.builder()
                .destinationStarportCode(destinationCode)
                .startStarportCode(originCode)
                .customerCode(missingCustomerCode)
                .shipCode(shipCode)
                .shipClass(ReserveBayCommand.ShipClass.SCOUT)
                .startAt(Instant.parse("2002-01-01T00:00:00Z"))
                .endAt(Instant.parse("2002-01-01T01:00:00Z"))
                .requestRoute(true)
                .build()
        when:
        createHoldReservationService.createHoldReservation(cmd)
        then:
        thrown(CustomerNotFoundException)
    }

    def "allocateHold – rzuca ShipNotFoundException gdy ship nie istnieje"() {
        given:
        def originCode = "ALPHA-BASE-SHIP-NF"
        def destinationCode = "DEF-SHIP-NF"
        def customerCode = "CUST-SHIP-NF"
        def existingShipCode = "SS-Enterprise-SHIP-NF-EXISTS"
        def missingShipCode = "SS-Enterprise-SHIP-NF-MISSING"

        seedDefaultReservationFixture(destinationCode, [
                originCode  : originCode,
                customerCode: customerCode,
                shipCode    : existingShipCode
        ])

        def cmd = ReserveBayCommand.builder()
                .destinationStarportCode(destinationCode)
                .startStarportCode(originCode)
                .customerCode(customerCode)
                .shipCode(missingShipCode)
                .shipClass(ReserveBayCommand.ShipClass.SCOUT)
                .startAt(Instant.parse("2002-01-01T00:00:00Z"))
                .endAt(Instant.parse("2002-01-01T01:00:00Z"))
                .requestRoute(true)
                .build()
        when:
        createHoldReservationService.createHoldReservation(cmd)
        then:
        thrown(ShipNotFoundException)
    }

    def "allocateHold – rzuca NoDockingBaysAvailableException gdy nie ma dostępnego miejsca na podaną godzinę"() {
        given:
        def originCode = "ALPHA-BASE-NO-BAY"
        def destinationCode = "DEF-NO-BAY"
        def customerCode = "CUST-NO-BAY"
        def shipCode = "SS-Enterprise-NO-BAY"

        seedDefaultReservationFixture(destinationCode, [
                originCode  : originCode,
                customerCode: customerCode,
                shipCode    : shipCode
        ])

        def cmd = ReserveBayCommand.builder()
                .destinationStarportCode(destinationCode)
                .startStarportCode(originCode)
                .customerCode(customerCode)
                .shipCode(shipCode)
                .shipClass(ReserveBayCommand.ShipClass.SCOUT)
                .startAt(Instant.parse("2002-01-01T00:00:00Z"))
                .endAt(Instant.parse("2002-01-01T01:00:00Z"))
                .requestRoute(true)
                .build()
        when:
        createHoldReservationService.createHoldReservation(cmd)
        createHoldReservationService.createHoldReservation(cmd)
        then:
        thrown(NoDockingBaysAvailableException)
    }
}
