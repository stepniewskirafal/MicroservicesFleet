package com.galactic.starport.service

import com.galactic.starport.BaseAcceptanceSpec
import org.springframework.beans.factory.annotation.Autowired

import java.time.Instant

class ReserveBayValidationCompositeTest extends BaseAcceptanceSpec {

    @Autowired
    ReserveBayValidationComposite validator

    private static final String DEST = "DEF"

    def setup() {
        purgeAndReset()
        seedDefaultReservationFixture(DEST, [destinationName: "Alpha Base Central"])
    }

    def "valid command passes when all preconditions are met"() {
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
        validator.validate(cmd)

        then:
        noExceptionThrown()
    }


    def "rejects when destination starport does not exist"() {
        given:
        def cmd = ReserveBayCommand.builder()
                .destinationStarportCode("NO-SUCH-DEST")
                .startStarportCode("ALPHA-BASE")
                .customerCode("CUST-001")
                .shipCode("SS-Enterprise-01")
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
        def cmd = ReserveBayCommand.builder()
                .destinationStarportCode(DEST)
                .startStarportCode("NO-SUCH-START")
                .customerCode("CUST-001")
                .shipCode("SS-Enterprise-01")
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
        def cmd = ReserveBayCommand.builder()
                .destinationStarportCode(DEST)
                .startStarportCode("NO-SUCH-START")
                .customerCode("CUST-001")
                .shipCode("SS-Enterprise-01")
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
        def cmd = ReserveBayCommand.builder()
                .destinationStarportCode(DEST)
                .startStarportCode("ALPHA-BASE")
                .customerCode("CUST-001")
                .shipCode("SS-Enterprise-01")
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
        startAt                               | endAt
        Instant.parse("2027-02-09T08:00:00Z") | Instant.parse("2027-02-09T08:00:00Z")
        Instant.parse("2027-02-09T10:00:00Z") | Instant.parse("2027-02-09T09:00:00Z")
    }

}
