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
    void purgeAndReset(String code) {
        cleanupStarport(code)
        resetSequences("starport_seq", "docking_bay_seq", "reservation_seq", "route_seq")
    }

    void seedStarportWithBay(Map args) {
        String code = args.code as String
        String name = args.name as String
        String bayLabel = args.bayLabel as String
        String shipClass = args.shipClass as String

        jdbc.update("insert into starport(code, name) values (?, ?)", code, name)
        Long starportId = jdbc.queryForObject("select id from starport where code = ? order by id desc limit 1", Long.class, code)
        jdbc.update("insert into docking_bay(starport_id, bay_label, ship_class, status) values (?,?,?,?)",
                starportId, bayLabel, shipClass, "AVAILABLE")
    }

    void cleanupStarport(String code) {
        jdbc.update("delete from route where reservation_id in (select id from reservation where docking_bay_id in (select id from docking_bay where starport_id in (select id from starport where code = ?)))", code)
        jdbc.update("delete from reservation where docking_bay_id in (select id from docking_bay where starport_id in (select id from starport where code = ?))", code)
        jdbc.update("delete from docking_bay where starport_id in (select id from starport where code = ?)", code)
        jdbc.update("delete from starport where code = ?", code)
    }

    void resetSequences(String... seqNames) {
        seqNames.each { seq -> jdbc.update("ALTER SEQUENCE IF EXISTS ${seq} RESTART WITH 1") }
    }

    int reservationsCount(String code) {
        jdbc.queryForObject(
                '''select count(*)
                   from reservation r
                   join docking_bay d on r.docking_bay_id = d.id
                   join starport s on d.starport_id = s.id
                   where s.code = ?''', Integer.class, code)
    }

    int routesCount(String code) {
        jdbc.queryForObject(
                '''select count(*)
                   from route t
                   join reservation r on t.reservation_id = r.id
                   join docking_bay d on r.docking_bay_id = d.id
                   join starport s on d.starport_id = s.id
                   where s.code = ?''', Integer.class, code)
    }

    Map makePayload(Map overrides = [:]) {
        Map base = [
                customerId         : "12345",
                shipId             : "SS-Enterprise-01",
                shipClass          : "SCOUT",
                startAt            : "2025-12-05T04:00:00Z",
                endAt              : "2025-12-05T05:00:00Z",
                requestRoute       : true,
                destinationPortCode: "ALPHA-BASE"
        ]
        base + overrides
    }
}
