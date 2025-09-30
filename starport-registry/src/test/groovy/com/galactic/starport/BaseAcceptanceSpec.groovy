package com.galactic.starport

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import spock.lang.Specification
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.spock.Testcontainers
import org.springframework.boot.testcontainers.service.connection.ServiceConnection

@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
abstract class BaseAcceptanceSpec extends Specification {

    @ServiceConnection
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("app")
            .withUsername("test")
            .withPassword("test")

    @LocalServerPort
    int port

    @Autowired
    JdbcTemplate jdbc

    @Autowired
    TestRestTemplate rest

    @Autowired
    ObjectMapper objectMapper

    // ----------------------------- HTTP -------------------------------------
    String api(String path) { "http://localhost:${port}${path}" }

    ResponseEntity<String> postReservation(String starportCode, Map body) {
        HttpHeaders headers = new HttpHeaders()
        headers.setContentType(MediaType.APPLICATION_JSON)
        return rest.postForEntity(api("/api/v1/starports/${starportCode}/reservations"), new HttpEntity<>(body, headers), String)
    }

    Map parseJson(ResponseEntity<String> resp) {
        objectMapper.readValue(resp.body, Map)
    }

    // ---------------------------- DATA DSL ----------------------------------
    void purgeAndReset() {
        truncateDomainTables()
    }

    Map seedDefaultReservationFixture(String destinationCode, Map overrides = [:]) {
        Map config = [
                originCode             : "ALPHA-BASE",
                originName             : "Alpha Base",
                originDescription      : null,
                destinationCode        : destinationCode,
                destinationName        : "${destinationCode} Starport",
                destinationDescription : null,
                destinationBayLabel    : "1",
                destinationBayShipClass: "SCOUT",
                destinationBayStatus   : "AVAILABLE",
                customerCode           : "CUST-001",
                customerName           : "Default Customer",
                shipCode               : "SS-Enterprise-01",
                shipClass              : "SCOUT"
        ] + overrides

        Long originId = jdbc.queryForObject(
                "insert into starport(code, name, description) values (?, ?, ?) returning id",
                Long.class,
                config.originCode,
                config.originName,
                config.originDescription)

        Long destinationId = jdbc.queryForObject(
                "insert into starport(code, name, description) values (?, ?, ?) returning id",
                Long.class,
                config.destinationCode,
                config.destinationName,
                config.destinationDescription)

        Long dockingBayId = jdbc.queryForObject(
                "insert into docking_bay(starport_id, bay_label, ship_class, status) values (?,?,?,?) returning id",
                Long.class,
                destinationId,
                config.destinationBayLabel,
                config.destinationBayShipClass,
                config.destinationBayStatus)

        Long customerId = jdbc.queryForObject(
                "insert into customer(customer_code, name) values (?, ?) returning id",
                Long.class,
                config.customerCode,
                config.customerName)

        Long shipId = jdbc.queryForObject(
                "insert into ship(customer_id, ship_code, ship_class) values (?,?,?) returning id",
                Long.class,
                customerId,
                config.shipCode,
                config.shipClass)

        [
                originStarportId     : originId,
                destinationStarportId: destinationId,
                dockingBayId         : dockingBayId,
                customerId           : customerId,
                shipId               : shipId
        ]
    }

    void truncateDomainTables() {
        jdbc.execute("TRUNCATE TABLE route, reservation, docking_bay, starport, ship, customer RESTART IDENTITY CASCADE")
    }

    int reservationsCount(String code) {
        jdbc.queryForObject(
                '''select count(*)
                   from reservation r
                   join docking_bay d on r.docking_bay_id = d.id
                   join starport s on d.starport_id = s.id
                   where s.code = ?''', Integer.class, code)
    }

    Map makePayload(Map overrides = [:]) {
        Map base = [
                customerCode       : "CUST-001",
                shipCode           : "SS-Enterprise-01",
                shipClass          : "SCOUT",
                startAt            : "2025-12-05T04:00:00Z",
                endAt              : "2025-12-05T05:00:00Z",
                requestRoute       : true,
                originPortId       : "ALPHA-BASE"
        ]
        base + overrides
    }

}