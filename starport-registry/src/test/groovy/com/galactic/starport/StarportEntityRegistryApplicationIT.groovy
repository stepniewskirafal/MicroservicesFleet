package com.galactic.starport

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import spock.lang.Stepwise

@Stepwise
class StarportEntityRegistryApplicationIT extends BaseAcceptanceSpec {

    @Autowired
    TestRestTemplate rest

    @Autowired
    ObjectMapper objectMapper

    def setup() {
        cleanupFixtures("ABC")
    }

    // ============ ATDD SCENARIUSZE ============

    def "ATDD: Happy path – 201 Created, poprawne body i zapis w DB (+route)"() {
        given: "Dostępny starport ABC i bay SCOUT w stanie ACTIVE"
        def ids = seedStarportWithBay(code: "ABC", name: "Alpha Base Central", bayLabel: "A-01", shipClass: "SCOUT")

        and: "Payload klienta (prośba o trasę)"
        Map body = [
                shipId             : "SS-Enterprise-01",
                shipClass          : "SCOUT",
                startAt            : "2025-12-05T02:00:00Z",
                endAt              : "2025-12-05T03:00:00Z",
                requestRoute       : true,
                destinationPortCode: "ALPHA-BASE"
        ]

        when: "Wysyłamy POST /api/v1/starports/ABC/reservations"
        def resp = postJson(api("/api/v1/starports/ABC/reservations"), body)

        then: "Otrzymujemy 201 i kompletne body odpowiedzi"
        resp.statusCode == HttpStatus.CREATED
        Map json = parse(resp)
        assert json.reservationId
        assert json.starportId
        assert json.dockingBayId
        assert json.startAt == "2025-12-05T02:00:00Z"
        assert json.endAt   == "2025-12-05T03:00:00Z"
        assert json.feeCharged instanceof Number
        assert json.route && json.route.routeId && json.route.etaLY instanceof Number && json.route.riskScore instanceof Number

        and: "Rezerwacja istnieje w DB (po ship_identifier + bay)"
        Integer cnt = jdbc.queryForObject(
                "select count(*) from reservation where docking_bay_id = ? and ship_identifier = ?",
                Integer, ids.bayId, "SS-Enterprise-01")
        assert cnt == 1

        and: "Utworzono trasę dla tej rezerwacji"
        Integer routeCnt = jdbc.queryForObject(
                "select count(*) from route r where r.reservation_id in (select id from reservation where docking_bay_id = ? and ship_identifier = ?) and r.destination_code = ?",
                Integer, ids.bayId, "SS-Enterprise-01", "ALPHA-BASE")
        assert routeCnt == 1
    }

/*
    def "ATDD: Walidacja – endAt przed startAt -> 400/422 i brak zapisu"() {
        given:
        def ids = seedStarportWithBay(code: "ABC", name: "Alpha Base Central", bayLabel: "A-01", shipClass: "SCOUT")

        and:
        Map body = [
                shipId       : "SS-Enterprise-01",
                shipClass    : "SCOUT",
                startAt      : "2025-12-05T04:00:00Z",
                endAt        : "2025-12-05T03:00:00Z", // end < start
                requestRoute : true,
                destinationPortCode: "ALPHA-BASE"
        ]

        when:
        def resp = postJson(api("/api/v1/starports/ABC/reservations"), body)

        then: "Zwraca 400 lub 422"
        resp.statusCode in [HttpStatus.BAD_REQUEST, HttpStatus.UNPROCESSABLE_ENTITY]

        and: "Brak zapisu rezerwacji"
        Integer cnt = jdbc.queryForObject(
                "select count(*) from reservation where docking_bay_id = ? and ship_identifier = ?",
                Integer, ids.bayId, "SS-Enterprise-01")
        assert cnt == 0
    }

    def "ATDD: Konflikt – druga rezerwacja tego samego slotu -> 409 i jedna encja w DB"() {
        given:
        def ids = seedStarportWithBay(code: "ABC", name: "Alpha Base Central", bayLabel: "A-01", shipClass: "SCOUT")
        Map body = [
                shipId       : "SS-Enterprise-01",
                shipClass    : "SCOUT",
                startAt      : "2025-12-05T05:00:00Z",
                endAt        : "2025-12-05T06:00:00Z",
                requestRoute : true,
                destinationPortCode: "ALPHA-BASE"
        ]

        when: "Pierwsze żądanie"
        def r1 = postJson(api("/api/v1/starports/ABC/reservations"), body)

        then:
        r1.statusCode == HttpStatus.CREATED

        when: "Drugie identyczne żądanie"
        def r2 = postJson(api("/api/v1/starports/ABC/reservations"), body)

        then:
        r2.statusCode == HttpStatus.CONFLICT

        and: "W bazie znajduje się tylko jedna rezerwacja dla tego bay i statku"
        Integer cnt = jdbc.queryForObject(
                "select count(*) from reservation where docking_bay_id = ? and ship_identifier = ?",
                Integer, ids.bayId, "SS-Enterprise-01")
        assert cnt == 1
    }

    def "ATDD: requestRoute=false – 201 bez pola route i bez wpisu w tabeli route"() {
        given:
        def ids = seedStarportWithBay(code: "ABC", name: "Alpha Base Central", bayLabel: "A-01", shipClass: "SCOUT")
        Map body = [
                shipId       : "SS-Enterprise-01",
                shipClass    : "SCOUT",
                startAt      : "2025-12-05T07:00:00Z",
                endAt        : "2025-12-05T08:00:00Z",
                requestRoute : false
        ]

        when:
        def resp = postJson(api("/api/v1/starports/ABC/reservations"), body)

        then:
        resp.statusCode == HttpStatus.CREATED
        Map json = parse(resp)
        assert json.route == null || json.route == [:]

        and: "Brak wpisu w tabeli route dla tej rezerwacji"
        Integer routeCnt = jdbc.queryForObject(
                "select count(*) from route r where r.reservation_id in (select id from reservation where docking_bay_id = ? and ship_identifier = ?)",
                Integer, ids.bayId, "SS-Enterprise-01")
        assert routeCnt == 0
    }

    def "ATDD: requestRoute=true i brak destinationPortCode -> 400/422"() {
        given:
        seedStarportWithBay(code: "ABC", name: "Alpha Base Central", bayLabel: "A-01", shipClass: "SCOUT")
        Map body = [
                shipId       : "SS-Enterprise-01",
                shipClass    : "SCOUT",
                startAt      : "2025-12-05T09:00:00Z",
                endAt        : "2025-12-05T10:00:00Z",
                requestRoute : true
                // brak destinationPortCode
        ]

        when:
        def resp = postJson(api("/api/v1/starports/ABC/reservations"), body)

        then:
        resp.statusCode in [HttpStatus.BAD_REQUEST, HttpStatus.UNPROCESSABLE_ENTITY]
    }
*/

    // ============ HELPERS ============

    private Map seedStarportWithBay(Map args) {
        String code = args.code
        String name = args.name
        String bayLabel = args.bayLabel
        String shipClass = args.shipClass

        jdbc.update("insert into starport(code, name) values (?, ?)", code, name)
        Long starportId = jdbc.queryForObject("select id from starport where code = ? order by id desc limit 1", Long.class, code)
        jdbc.update("insert into docking_bay(starport_id, bay_label, ship_class, status) values (?,?,?,?)",
                starportId, bayLabel, shipClass, "ACTIVE")
        Long bayId = jdbc.queryForObject("select id from docking_bay where starport_id = ? and bay_label = ? order by id desc limit 1",
                Long.class, starportId, bayLabel)
        return [starportId: starportId, bayId: bayId]
    }

    private void cleanupFixtures(String code) {
        // kolejność usuwania: route -> reservation -> docking_bay -> starport (brak FK, więc explicit)
        jdbc.update("delete from route where reservation_id in (select id from reservation where docking_bay_id in (select id from docking_bay where starport_id in (select id from starport where code = ?)))", code)
        jdbc.update("delete from reservation where docking_bay_id in (select id from docking_bay where starport_id in (select id from starport where code = ?))", code)
        jdbc.update("delete from docking_bay where starport_id in (select id from starport where code = ?)", code)
        jdbc.update("delete from starport where code = ?", code)
    }

    private ResponseEntity<String> postJson(String url, Map body) {
        HttpHeaders headers = new HttpHeaders()
        headers.setContentType(MediaType.APPLICATION_JSON)
        return rest.postForEntity(url, new HttpEntity<>(body, headers), String)
    }

    private Map parse(ResponseEntity<String> resp) {
        objectMapper.readValue(resp.body, Map)
    }
}
