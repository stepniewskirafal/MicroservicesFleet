package com.galactic.starport

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import spock.lang.Narrative
import spock.lang.Title

@Title("ATDD: obsługa błędów i cykl życia rezerwacji – scenariusze integracyjne")
@Narrative('''
Jako klient API chcę otrzymywać jasne odpowiedzi na niepoprawne żądania
i móc przejść przez cały proces rezerwacji (zajęcie, potwierdzenie i
zwolnienie) bez konieczności znajomości wewnętrznej implementacji.
''')
class ReservationApiTest extends BaseAcceptanceSpec {

    @Autowired
    TestRestTemplate rest

    @Autowired
    ObjectMapper objectMapper

    private static final String STARPORT = "DEF"

    def setup() {
        purgeAndReset()
        seedDefaultReservationFixture(STARPORT, [destinationName: "Alpha Base Central"])
    }


    def "Błąd: brak wymaganych pól – customerCode null"() {
        given: "Payload bez customerCode"
        // zamiast null usuwamy pole całkowicie, aby symulować jego brak
        Map body = makePayload([:])
        body.remove('customerCode')

        when: "Wywołujemy API"
        def resp = postReservation(STARPORT, body)

        then: "Otrzymujemy status 422 (UNPROCESSABLE_ENTITY) z walidatora lub 400"
        resp.statusCode.value() in [HttpStatus.BAD_REQUEST.value(), HttpStatus.UNPROCESSABLE_ENTITY.value()]

        and: "W bazie brak rezerwacji"
        reservationsCount(STARPORT) == 0
    }

    def "Błąd: nieistniejący port – kod starportu nieznany"() {
        given: "Poprawny payload"
        Map body = makePayload([:])

        when: "Podajemy nieistniejący kod portu"
        def resp = postReservation("UNKNOWN", body)

        then: "Otrzymujemy 404 Not Found"
        resp.statusCode == HttpStatus.NOT_FOUND

        and: "W bazie nie ma wpisów dla nowej jednostki"
        reservationsCount(STARPORT) == 0
    }

    def "Błąd: niepoprawny shipClass"() {
        given: "Payload z nieobsługiwaną klasą statku"
        Map body = makePayload([shipClass: "UNKNOWN-CLASS"])

        when:
        def resp = postReservation(STARPORT, body)

        then: "Otrzymujemy 400 Bad Request lub 422 Unprocessable Entity"
        resp.statusCode.value() in [HttpStatus.BAD_REQUEST.value(), HttpStatus.UNPROCESSABLE_ENTITY.value()]

        and: "Brak nowych rezerwacji w DB"
        reservationsCount(STARPORT) == 0
    }

    def "Błąd: niepoprawna struktura JSON – całkowicie błędne body"() {
        given:
        // zamiast mapy wysyłamy string – TestRestTemplate opakuje go jako String body
        HttpHeaders headers = new HttpHeaders()
        headers.setContentType(MediaType.APPLICATION_JSON)
        HttpEntity<String> entity = new HttpEntity<>("{ błąd json", headers)

        when:
        def resp = rest.postForEntity(api("/api/v1/starports/${STARPORT}/reservations"), entity, String)

        then:
        resp.statusCode == HttpStatus.BAD_REQUEST
        reservationsCount(STARPORT) == 0
    }
}