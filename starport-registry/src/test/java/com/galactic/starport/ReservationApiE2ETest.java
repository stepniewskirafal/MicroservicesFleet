package com.galactic.starport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceAccessMode;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * ATDD: obsługa błędów i cykl życia rezerwacji – scenariusze integracyjne.
 *
 * <p>Jako klient API chcę otrzymywać jasne odpowiedzi na niepoprawne żądania i móc przejść przez
 * cały proces rezerwacji (zajęcie, potwierdzenie i zwolnienie) bez konieczności znajomości
 * wewnętrznej implementacji.
 */
@ResourceLock(value = "DB_TRUNCATE", mode = ResourceAccessMode.READ_WRITE)
class ReservationApiE2ETest extends BaseAcceptanceTest {

    private static final String STARPORT = "DEF";

    @BeforeEach
    void setup() {
        purgeAndReset();
        seedDefaultReservationFixture(STARPORT, Map.of("destinationName", "Alpha Base Central"));
    }

    @Test
    void errorMissingRequiredFields_customerCodeNull() {
        // given - payload bez customerCode
        Map<String, Object> body = makePayload(new HashMap<>());
        body.remove("customerCode");

        // when
        ResponseEntity<String> resp = postReservation(STARPORT, body);

        // then - 422 (UNPROCESSABLE_ENTITY) lub 400
        assertTrue(
                resp.getStatusCode().value() == HttpStatus.BAD_REQUEST.value()
                        || resp.getStatusCode().value() == HttpStatus.UNPROCESSABLE_ENTITY.value(),
                "Expected 400 or 422 but got: " + resp.getStatusCode());

        // and - w bazie brak rezerwacji
        assertEquals(0, reservationsCount(STARPORT));
    }

    @Test
    void errorUnknownStarportCode() {
        // given - poprawny payload
        Map<String, Object> body = makePayload(Map.of());

        // when - podajemy nieistniejący kod portu
        ResponseEntity<String> resp = postReservation("UNKNOWN", body);

        // then - 404 Not Found
        assertEquals(HttpStatus.NOT_FOUND, resp.getStatusCode());

        // and - w bazie nie ma wpisów dla nowej jednostki
        assertEquals(0, reservationsCount(STARPORT));
    }

    @Test
    void errorInvalidShipClass() {
        // given - payload z nieobsługiwaną klasą statku
        Map<String, Object> body = makePayload(Map.of("shipClass", "UNKNOWN-CLASS"));

        // when
        ResponseEntity<String> resp = postReservation(STARPORT, body);

        // then - 400 lub 422
        assertTrue(
                resp.getStatusCode().value() == HttpStatus.BAD_REQUEST.value()
                        || resp.getStatusCode().value() == HttpStatus.UNPROCESSABLE_ENTITY.value(),
                "Expected 400 or 422 but got: " + resp.getStatusCode());

        // and - brak nowych rezerwacji w DB
        assertEquals(0, reservationsCount(STARPORT));
    }

    @Test
    void errorMalformedJson() {
        // given - całkowicie błędne body JSON
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<String> entity = new HttpEntity<>("{ błąd json", headers);

        // when
        ResponseEntity<String> resp =
                rest.postForEntity(api("/api/v1/starports/" + STARPORT + "/reservations"), entity, String.class);

        // then
        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
        assertEquals(0, reservationsCount(STARPORT));
    }
}
