package com.galactic.starport.service

import com.galactic.starport.BaseAcceptanceSpec
import org.springframework.beans.factory.annotation.Autowired

import java.time.Instant

class FeeCalculatorServiceTest extends BaseAcceptanceSpec {

    private static final String DEST = "DEF"

    @Autowired
    FeeCalculatorService feeCalculatorService

    def "calculateFee computes correct fee for different ship classes"() {
        given:
        def start = Instant.parse("2004-01-01T00:00:00Z")
        def cmd = ReserveBayCommand.builder()
                .destinationStarportCode(DEST)
                .startStarportCode("ALPHA-BASE")
                .customerCode("CUST-001")
                .shipCode("SS-Enterprise-01")
                .shipClass(shipClass)
                .startAt(start)
                .endAt(start.plusSeconds(hours * 3600L))
                .requestRoute(true)
                .build()

        when:
        def fee = feeCalculatorService.calculateFee(cmd)

        then:
        fee == expectedFee

        where:
        shipClass                             | hours || expectedFee
        ReserveBayCommand.ShipClass.SCOUT     | 1L    || BigDecimal.valueOf(50)
        ReserveBayCommand.ShipClass.FREIGHTER | 2L    || BigDecimal.valueOf(240)
    }

    def "calculateFee throws IllegalArgumentException when end time is before start time"() {
        given:
        def cmd = ReserveBayCommand.builder()
                .destinationStarportCode(DEST)
                .startStarportCode("ALPHA-BASE")
                .customerCode("CUST-001")
                .shipCode("SS-Enterprise-01")
                .shipClass(ReserveBayCommand.ShipClass.SCOUT)
                .startAt(Instant.parse("2004-01-01T01:00:00Z"))
                .endAt(Instant.parse("2004-01-01T00:00:00Z"))
                .requestRoute(true)
                .build()

        when:
        feeCalculatorService.calculateFee(cmd)

        then:
        thrown(InvalidReservationTimeException)
    }
}
