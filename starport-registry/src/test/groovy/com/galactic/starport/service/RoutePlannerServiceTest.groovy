package com.galactic.starport.service

import com.galactic.starport.BaseAcceptanceSpec
import com.galactic.starport.repository.StarportPersistenceFacade
import org.springframework.beans.factory.annotation.Autowired

import java.time.Instant

class RoutePlannerServiceTest extends BaseAcceptanceSpec {
    @Autowired
    RoutePlannerService routePlannerService
    @Autowired
    StarportPersistenceFacade starportPersistenceFacade

    def "should calculate route when requested"() {
        given:
        def originCode = "ALPHA-BASE-ROUTE"
        def destinationCode = "DEF-ROUTE"
        def customerCode = "CUST-ROUTE"
        def shipCode = "SS-Enterprise-ROUTE"

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
                .startAt(Instant.parse("2008-01-01T00:00:00Z"))
                .endAt(Instant.parse("2008-01-01T01:00:00Z"))
                .requestRoute(true)
                .build()
        when:
        def routeOpt = routePlannerService.calculateRoute(cmd)
        then:
        with(routeOpt.get()) {
            routeCode.contains("ROUTE-${cmd.startStarportCode()}-${cmd.destinationStarportCode()}")
            startStarportCode == cmd.startStarportCode()
            destinationStarportCode == cmd.destinationStarportCode()
            etaLightYears > 0
            riskScore >= 0
            active
        }
    }

    def "should not calculate route when not requested"() {
        given:
        def originCode = "ALPHA-BASE-NO-ROUTE"
        def destinationCode = "DEF-NO-ROUTE"
        def customerCode = "CUST-NO-ROUTE"
        def shipCode = "SS-Enterprise-NO-ROUTE"

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
                .startAt(Instant.parse("2008-01-01T00:00:00Z"))
                .endAt(Instant.parse("2008-01-01T01:00:00Z"))
                .requestRoute(true)
                .build()
        when:
        def routeOpt = routePlannerService.calculateRoute(cmd)
        then:
        with(routeOpt.get()) {
            routeCode.contains("ROUTE-${cmd.startStarportCode()}-${cmd.destinationStarportCode()}")
            startStarportCode == cmd.startStarportCode()
            destinationStarportCode == cmd.destinationStarportCode()
            etaLightYears > 0
            riskScore >= 0
            active
        }
    }
}
