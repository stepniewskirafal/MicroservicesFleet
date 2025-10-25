package com.galactic.starport

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.http.*
import spock.lang.Narrative
import spock.lang.Stepwise
import spock.lang.Title

@Title("ATDD: tworzenie rezerwacji – scenariusze brzegowe")
@Narrative('''
Jako klient API chcę tworzyć rezerwacje miejsc dokowania.
Scenariusze obejmują walidację, konflikt współbieżny oraz flagę requestRoute.
''')
@Stepwise
class ReservationCreationTest extends BaseAcceptanceSpec {

    @Autowired
    TestRestTemplate rest

    @Autowired
    ObjectMapper objectMapper

    private static final String STARPORT = "ABC"

    def setup() {
        purgeAndReset(STARPORT)
        seedStarportWithBay(code: STARPORT, name: "Alpha Base Central", bayLabel: "1", shipClass: "SCOUT")
    }

    def "ATDD: 201 Created – happy path (requestRoute=false)"() {
        given: "Poprawny payload bez żądania trasy"
        Map body = makePayload([
                requestRoute: false,
        ])

        when: "Wysyłamy POST"
        def resp = postReservation(STARPORT, body)

        then: "Status 201"
        resp.statusCode == HttpStatus.CREATED

        and: "JSON bez pola route lub route == null"
        Map json = parseJson(resp)
        assert !json.containsKey('route') || json.route == null

        and: "W bazie: 1 rezerwacja i 0 tras"
        reservationsCount(STARPORT) == 1
        routesCount(STARPORT) == 0
    }

    def "ATDD: Walidacja – endAt przed startAt -> 400/422 i brak zapisu"() {
        given: "Payload z błędną kolejnością dat"
        Map body = makePayload([
                startAt: "2025-12-05T05:00:00Z",
                endAt  : "2025-12-05T04:00:00Z",
                requestRoute: false
        ])

        when:
        def resp = postReservation(STARPORT, body)

        then: "Status 400 lub 422 (w zależności od implementacji walidacji)"
        resp.statusCode.value() in [HttpStatus.UNPROCESSABLE_ENTITY.value()]

        and: "W bazie brak rezerwacji i tras"
        reservationsCount(STARPORT) == 0
        routesCount(STARPORT) == 0
    }

    def "ATDD: Konflikt – druga rezerwacja tego samego slotu -> 409 i jedna encja w DB"() {
        given: "Istnieje już rezerwacja na dany slot"
        Map first = makePayload([
                startAt: "2025-12-05T04:00:00Z",
                endAt  : "2025-12-05T05:00:00Z",
                requestRoute: false
        ])
        def created = postReservation(STARPORT, first)
        assert created.statusCode == HttpStatus.CREATED

        and: "Drugi payload kolidujący czasowo"
        Map second = makePayload([
                startAt: "2025-12-05T04:30:00Z", // nachodzi na istniejącą
                endAt  : "2025-12-05T04:45:00Z",
                requestRoute: false
        ])

        when:
        def conflict = postReservation(STARPORT, second)

        then: "Otrzymujemy 409 Conflict"
        conflict.statusCode == HttpStatus.CONFLICT

        and: "W bazie nadal tylko jedna rezerwacja"
        reservationsCount(STARPORT) == 1
    }

    def "ATDD: requestRoute=false – 201 bez pola route i bez wpisu w tabeli route"() {
        given:
        Map body = makePayload([
                requestRoute: false
        ])

        when:
        def resp = postReservation(STARPORT, body)

        then:
        resp.statusCode == HttpStatus.CREATED
        Map json = parseJson(resp)
        assert !json.containsKey('route') || json.route == null

        and:
        routesCount(STARPORT) == 0
    }

/*    def "ATDD: requestRoute=true i brak destinationPortCode -> 400/422"() {
        given: "Żądamy trasy, ale nie podajemy destinationPortCode"
        Map body = makePayload([
                requestRoute: true
                // brak destinationPortCode
        ])

        when:
        def resp = postReservation(STARPORT, body)

        then:
        resp.statusCode.value() in [HttpStatus.BAD_REQUEST.value(), HttpStatus.UNPROCESSABLE_ENTITY.value()]

        and: "Bez śladów w DB"
        reservationsCount(STARPORT) == 0
        routesCount(STARPORT) == 0
    }*/
}
