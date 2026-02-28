package com.galactic.starport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.parallel.ResourceAccessMode;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * ATDD: obsługa błędów i cykl życia rezerwacji – scenariusze integracyjne.
 *
 */
//

class ReservationLifecycleE2ETest extends BaseAcceptanceTest {

    // Unikalne kody per klasa – brak konfliktu z ReservationApiE2ETest (używa "DEF", "CUST-001", "SS-Enterprise-01")
    private static final String STARPORT = "DEF-LIFECYCLE";
    private static final String CUSTOMER_CODE = "CUST-LC-001";
    private static final String SHIP_CODE = "SS-LC-Enterprise-01";

    @Test
    void lifecycleCreateAndConfirmReservationWithRoute() throws Exception {
        // given - Utworzenie rezerwacji z planowaniem trasy
        seedDefaultReservationFixture(
                STARPORT,
                Map.of(
                        "destinationName", "Alpha Base Central",
                        "customerCode", CUSTOMER_CODE,
                        "customerName", "Lifecycle Customer",
                        "shipCode", SHIP_CODE));

        Instant start = Instant.now().plusSeconds(100);
        Instant end = start.plusSeconds(60);
        Map<String, Object> withRoute = makePayload(Map.of(
                "requestRoute", true,
                "startAt", start.toString(),
                "endAt", end.toString(),
                "customerCode", CUSTOMER_CODE,
                "shipCode", SHIP_CODE));

        // when - Wywołanie API
        ResponseEntity<String> resp = postReservation(STARPORT, withRoute);

        // then - Rezerwacja jest potwierdzona i ma trasę
        assertEquals(HttpStatus.CREATED, resp.getStatusCode());
        // ID z odpowiedzi – brak race condition (nie ma "order by id desc limit 1")
        Long id = ((Number) parseJson(resp).get("reservationId")).longValue();
        assertEquals(
                "CONFIRMED", jdbc.queryForObject("select status from reservation where id = ?", String.class, id));
        assertNotNull(
                jdbc.queryForObject("select fee_charged from reservation where id = ?", java.math.BigDecimal.class, id));
        assertEquals(
                1,
                jdbc.queryForObject("select count(*) from route where reservation_id = ?", Integer.class, id));
    }

    @Test
    void lifecycleCreateAndConfirmReservationWithoutRoute() throws Exception {
        // given - Utworzenie rezerwacji bez planowania trasy
        // Przedział czasowy nie nakłada się z lifecycleCreateAndConfirmReservationWithRoute (200s > 160s)
        seedDefaultReservationFixture(
                STARPORT,
                Map.of(
                        "destinationName", "Alpha Base Central",
                        "customerCode", CUSTOMER_CODE,
                        "customerName", "Lifecycle Customer",
                        "shipCode", SHIP_CODE));

        Instant start = Instant.now().plusSeconds(200);
        Instant end = start.plusSeconds(60);
        Map<String, Object> noRoute = makePayload(Map.of(
                "requestRoute", false,
                "startAt", start.toString(),
                "endAt", end.toString(),
                "customerCode", CUSTOMER_CODE,
                "shipCode", SHIP_CODE));

        // when - Wywołanie API
        ResponseEntity<String> resp = postReservation(STARPORT, noRoute);
        assertEquals(HttpStatus.CREATED, resp.getStatusCode());

        // then - Rezerwacja jest potwierdzona bez trasy
        Long id = ((Number) parseJson(resp).get("reservationId")).longValue();
        assertEquals(
                "CONFIRMED", jdbc.queryForObject("select status from reservation where id = ?", String.class, id));
        assertNotNull(
                jdbc.queryForObject("select fee_charged from reservation where id = ?", java.math.BigDecimal.class, id));
        assertEquals(
                0,
                jdbc.queryForObject("select count(*) from route where reservation_id = ?", Integer.class, id));
    }
}