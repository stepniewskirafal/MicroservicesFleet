package com.galactic.starport.service

import com.galactic.starport.BaseAcceptanceSpec
import org.springframework.beans.factory.annotation.Autowired

import java.time.Instant

class ReserveBayValidationCompositeTest extends BaseAcceptanceSpec {

    @Autowired
    ReserveBayValidationComposite validator

    def "valid command passes when all preconditions are met"() {
        given:
        def originCode = "ALPHA-BASE-VALID"
        def destinationCode = "DEF-VALID"
        def customerCode = "CUST-VALID"
        def shipCode = "SS-Enterprise-VALID"

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
                .startAt(Instant.parse("2000-01-01T00:00:00Z"))
                .endAt(Instant.parse("2000-01-01T01:00:00Z"))
                .requestRoute(true)
                .build()

        when:
        validator.validate(cmd)

        then:
        noExceptionThrown()
    }

    def "rejects when destination starport does not exist"() {
        given:
        def originCode = "ALPHA-BASE-DEST-NF"
        def existingDestinationCode = "DEF-DEST-NF-EXISTS"
        def missingDestinationCode = "DEF-DEST-NF-MISSING"
        def customerCode = "CUST-DEST-NF"
        def shipCode = "SS-Enterprise-DEST-NF"

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
                .startAt(Instant.parse("2027-02-09T08:00:00Z"))
                .endAt(Instant.parse("2027-02-09T09:00:00Z"))
                .requestRoute(true)
                .build()

        when:
        validator.validate(cmd)

        then:
        thrown(StarportNotFoundException)
    }

    def "rejects when requestRoute=true and start starport does not exist"() {
        given:
        def existingOriginCode = "ALPHA-BASE-ROUTE-EXISTS"
        def missingStartCode = "ALPHA-BASE-ROUTE-MISSING"
        def destinationCode = "DEF-ROUTE"
        def customerCode = "CUST-ROUTE"
        def shipCode = "SS-Enterprise-ROUTE"

        seedDefaultReservationFixture(destinationCode, [
                originCode  : existingOriginCode,
                customerCode: customerCode,
                shipCode    : shipCode
        ])

        def cmd = ReserveBayCommand.builder()
                .destinationStarportCode(destinationCode)
                .startStarportCode(missingStartCode)
                .customerCode(customerCode)
                .shipCode(shipCode)
                .shipClass(ReserveBayCommand.ShipClass.SCOUT)
                .startAt(Instant.parse("2027-02-09T08:00:00Z"))
                .endAt(Instant.parse("2027-02-09T09:00:00Z"))
                .requestRoute(true)
                .build()

        when:
        validator.validate(cmd)

        then:
        thrown(StarportNotFoundException)
    }

    def "does not check start starport when requestRoute=false"() {
        given:
        def originCode = "ALPHA-BASE-ROUTE-FALSE"
        def destinationCode = "DEF-ROUTE-FALSE"
        def customerCode = "CUST-ROUTE-FALSE"
        def shipCode = "SS-Enterprise-ROUTE-FALSE"

        seedDefaultReservationFixture(destinationCode, [
                originCode  : originCode,
                customerCode: customerCode,
                shipCode    : shipCode
        ])

        def cmd = ReserveBayCommand.builder()
                .destinationStarportCode(destinationCode)
                .startStarportCode("NO-SUCH-START-ROUTE-FALSE")
                .customerCode(customerCode)
                .shipCode(shipCode)
                .shipClass(ReserveBayCommand.ShipClass.SCOUT)
                .startAt(Instant.parse("2027-02-09T08:00:00Z"))
                .endAt(Instant.parse("2027-02-09T09:00:00Z"))
                .requestRoute(false)
                .build()

        when:
        validator.validate(cmd)

        then:
        noExceptionThrown()
    }

    def "rejects invalid time ranges (start >= end)"() {
        given:
        def originCode = "ALPHA-BASE-TIME-${suffix}"
        def destinationCode = "DEF-TIME-${suffix}"
        def customerCode = "CUST-TIME-${suffix}"
        def shipCode = "SS-Enterprise-TIME-${suffix}"

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
                .startAt(startAt)
                .endAt(endAt)
                .requestRoute(true)
                .build()

        when:
        validator.validate(cmd)

        then:
        thrown(InvalidReservationTimeException)

        where:
        startAt                               | endAt                                 | suffix
        Instant.parse("2027-02-09T08:00:00Z") | Instant.parse("2027-02-09T08:00:00Z") | "EQ"
        Instant.parse("2027-02-09T10:00:00Z") | Instant.parse("2027-02-09T09:00:00Z") | "GT"
    }

}
