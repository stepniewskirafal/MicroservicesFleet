package com.galactic.starport;

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
class ReservationCreationIT extends BaseAcceptanceSpec {

    @Autowired
    TestRestTemplate rest

    @Autowired
    ObjectMapper objectMapper

    def setup() {
        // porządek danych testowych
        cleanupFixtures("ABC")

        jdbc.update("ALTER SEQUENCE IF EXISTS starport_seq RESTART WITH 1")
        jdbc.update("ALTER SEQUENCE IF EXISTS docking_bay_seq RESTART WITH 1")
        jdbc.update("ALTER SEQUENCE IF EXISTS reservation_seq RESTART WITH 1")
        jdbc.update("ALTER SEQUENCE IF EXISTS route_seq RESTART WITH 1")
    }

    def "should return 201 Created and expected body"() {
        given: "Starport ABC i aktywne miejsce dokujące #1 dla klasy SCOUT"
        seedStarportWithBay(code: "ABC", name: "Alpha Base Central", bayLabel: "1", shipClass: "SCOUT")

        and: "Payload identyczny z wymaganiem"
        Map body = [
        customerId         : "12345",
                shipId             : "SS-Enterprise-01",
                shipClass          : "SCOUT",
                startAt            : "2025-12-05T04:00:00Z",
                endAt              : "2025-12-05T05:00:00Z",
                requestRoute       : true,
                destinationPortCode: "ALPHA-BASE"
        ]

        when: "Wysyłamy POST"
        def resp = postJson(api("/api/v1/starports/ABC/reservations"), body)

        then: "Status 201 Created"
        resp.statusCode == HttpStatus.CREATED

        and: "Body jak w przykładzie (z tolerancją na losowe reservationId)"
        Map json = parse(resp)
        assert json.reservationId instanceof Number
        assert json.starportCode == "ABC"
        assert json.bayNumber == 1
        assert json.startAt == "2025-12-05T04:00:00Z"
        assert json.endAt   == "2025-12-05T05:00:00Z"
        assert json.feeCharged == null
        assert json.route == null
    }

    // ===== Helpers (lokalne kopie – KISS) =====

    private Map seedStarportWithBay(Map args) {
        String code = args.code
        String name = args.name
        String bayLabel = args.bayLabel
        String shipClass = args.shipClass

        jdbc.update("insert into starport(code, name) values (?, ?)", code, name)
        Long starportId = jdbc.queryForObject("select id from starport where code = ? order by id desc limit 1", Long.class, code)
        jdbc.update("insert into docking_bay(starport_id, bay_label, ship_class, status) values (?,?,?,?)",
                starportId, bayLabel, shipClass, "AVAILABLE")
        Long bayId = jdbc.queryForObject("select id from docking_bay where starport_id = ? and bay_label = ? order by id desc limit 1",
                Long.class, starportId, bayLabel)
        return [starportId: starportId, bayId: bayId]
    }

    private void cleanupFixtures(String code) {
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
