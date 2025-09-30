package com.galactic.starport

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.http.HttpStatus
import spock.lang.Narrative
import spock.lang.Title

@Title("ATDD: obsługa błędów i cykl życia rezerwacji – scenariusze integracyjne")
@Narrative('''
Jako klient API chcę otrzymywać jasne odpowiedzi na niepoprawne żądania
i móc przejść przez cały proces rezerwacji (zajęcie, potwierdzenie i
zwolnienie) bez konieczności znajomości wewnętrznej implementacji.
''')
class ReservationLifecycleTest extends BaseAcceptanceSpec {

    @Autowired
    TestRestTemplate rest

    @Autowired
    ObjectMapper objectMapper

    private static final String STARPORT = "DEF"

    def setup() {
        purgeAndReset()
        seedDefaultReservationFixture(STARPORT, [destinationName: "Alpha Base Central"])
    }

    def "Cykl życia: utworzenie i potwierdzenie rezerwacji z trasą"() {
        given: "Utworzenie rezerwacji bez planowania trasy (HOLD)"
        Map withRoute = makePayload([
                requestRoute: true,
                startAt: "2025-12-05T06:00:00Z",
                endAt  : "2025-12-05T07:00:00Z"
        ])
        when: "Utworzenie rezerwacji z planowaniem trasy (CONFIRMED)"
        def resp = postReservation(STARPORT, withRoute)

        then: "Rezerwacja jest potwierdzona i ma trasę"
        resp.statusCode == HttpStatus.CREATED
        Long id = jdbc.queryForObject("select id from reservation order by id desc limit 1", Long.class)
        assert jdbc.queryForObject("select status from reservation where id = ?", String.class, id) == "CONFIRMED"
        assert jdbc.queryForObject("select fee_charged from reservation where id = ?", BigDecimal.class, id) != null
        assert jdbc.queryForObject("select count(*) from route where reservation_id = ?", Integer.class, id) == 1
    }

    def "Cykl życia: utworzenie i potwierdzenie rezerwacji bez trasy"() {
        given: "Utworzenie rezerwacji bez planowania trasy (HOLD)"
        Map noRoute = makePayload([requestRoute: false])

        when: "Utworzenie rezerwacji z planowaniem trasy (CONFIRMED)"
        def resp = postReservation(STARPORT, noRoute)
        assert resp.statusCode == HttpStatus.CREATED

        then: "Rezerwacja jest potwierdzona i ma trasę"
        Long id = jdbc.queryForObject("select id from reservation order by id desc limit 1", Long.class)
        assert jdbc.queryForObject("select status from reservation where id = ?", String.class, id) == "CONFIRMED"
        assert jdbc.queryForObject("select fee_charged from reservation where id = ?", BigDecimal.class, id) != null
        assert jdbc.queryForObject("select count(*) from route where reservation_id = ?", Integer.class, id) == 0
    }
}