package com.galactic.starport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceAccessMode;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * ATDD: obsługa błędów i cykl życia rezerwacji – scenariusze integracyjne.
 *
 * <p>Jako klient API chcę otrzymywać jasne odpowiedzi na niepoprawne żądania i móc przejść przez
 * cały proces rezerwacji (zajęcie, potwierdzenie i zwolnienie) bez konieczności znajomości
 * wewnętrznej implementacji.
 */
@AutoConfigureObservability
@ResourceLock(value = "DB_TRUNCATE", mode = ResourceAccessMode.READ_WRITE)
class ReservationLifecycleE2ETest extends BaseAcceptanceTest {

    private static final String STARPORT = "DEF";

    @BeforeEach
    void setup() {
        purgeAndReset();
        seedDefaultReservationFixture(STARPORT, Map.of("destinationName", "Alpha Base Central"));
    }

    @Test
    void lifecycleCreateAndConfirmReservationWithRoute() {
        // given - Utworzenie rezerwacji z planowaniem trasy
        Instant start = Instant.now().plusSeconds(3600);
        Instant end = start.plusSeconds(3600);
        Map<String, Object> withRoute = makePayload(
                Map.of("requestRoute", true, "startAt", start.toString(), "endAt", end.toString()));

        // when - Wywołanie API
        ResponseEntity<String> resp = postReservation(STARPORT, withRoute);

        // then - Rezerwacja jest potwierdzona i ma trasę
        assertEquals(HttpStatus.CREATED, resp.getStatusCode());
        Long id = jdbc.queryForObject("select id from reservation order by id desc limit 1", Long.class);
        assertEquals(
                "CONFIRMED", jdbc.queryForObject("select status from reservation where id = ?", String.class, id));
        assertNotNull(
                jdbc.queryForObject("select fee_charged from reservation where id = ?", java.math.BigDecimal.class, id));
        assertEquals(
                1,
                jdbc.queryForObject(
                        "select count(*) from route where reservation_id = ?", Integer.class, id));
    }

    @Test
    void lifecycleCreateAndConfirmReservationWithoutRoute() {
        // given - Utworzenie rezerwacji bez planowania trasy (HOLD)
        Map<String, Object> noRoute = makePayload(Map.of("requestRoute", false));

        // when - Wywołanie API
        ResponseEntity<String> resp = postReservation(STARPORT, noRoute);
        assertEquals(HttpStatus.CREATED, resp.getStatusCode());

        // then - Rezerwacja jest potwierdzona bez trasy
        Long id = jdbc.queryForObject("select id from reservation order by id desc limit 1", Long.class);
        assertEquals(
                "CONFIRMED", jdbc.queryForObject("select status from reservation where id = ?", String.class, id));
        assertNotNull(
                jdbc.queryForObject("select fee_charged from reservation where id = ?", java.math.BigDecimal.class, id));
        assertEquals(
                0,
                jdbc.queryForObject(
                        "select count(*) from route where reservation_id = ?", Integer.class, id));
    }
}
