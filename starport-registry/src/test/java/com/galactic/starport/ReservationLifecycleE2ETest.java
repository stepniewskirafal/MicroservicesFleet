package com.galactic.starport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * ATDD: obsługa błędów i cykl życia rezerwacji – scenariusze integracyjne.
 */
class ReservationLifecycleE2ETest extends BaseAcceptanceTest {

    @Test
    void lifecycleCreateAndConfirmReservationWithRoute() throws Exception {
        // given - unikalne kody per metoda, brak konfliktu przy równoległym wykonaniu
        String starport = "DEF-LC-WR";
        String customerCode = "CUST-LC-WR";
        String shipCode = "SS-LC-WR-01";

        seedDefaultReservationFixture(
                starport,
                Map.of(
                        "originCode", "ALPHA-BASE-WR",
                        "destinationName", "Alpha Base Central",
                        "customerCode", customerCode,
                        "customerName", "Lifecycle Customer",
                        "shipCode", shipCode));

        Instant start = Instant.now().plusSeconds(100);
        Instant end = start.plusSeconds(60);
        Map<String, Object> withRoute = makePayload(Map.of(
                "requestRoute", true,
                "startAt", start.toString(),
                "endAt", end.toString(),
                "customerCode", customerCode,
                "shipCode", shipCode,
                "originPortId", "ALPHA-BASE-WR"));

        // when
        ResponseEntity<String> resp = postReservation(starport, withRoute);

        // then - rezerwacja potwierdzona z trasą
        assertEquals(HttpStatus.CREATED, resp.getStatusCode());
        Long id = ((Number) parseJson(resp).get("reservationId")).longValue();
        Map<String, Object> row = jdbc.queryForMap(
                "select r.status, r.fee_charged, count(rt.id) as route_count" +
                " from reservation r left join route rt on rt.reservation_id = r.id" +
                " where r.id = ? group by r.status, r.fee_charged", id);
        assertEquals("CONFIRMED", row.get("status"));
        assertNotNull(row.get("fee_charged"));
        assertEquals(1L, row.get("route_count"));
    }

    @Test
    void lifecycleCreateAndConfirmReservationWithoutRoute() throws Exception {
        // given - unikalne kody per metoda, brak konfliktu przy równoległym wykonaniu
        String starport = "DEF-LC-NR";
        String customerCode = "CUST-LC-NR";
        String shipCode = "SS-LC-NR-01";

        seedDefaultReservationFixture(
                starport,
                Map.of(
                        "originCode", "ALPHA-BASE-NR",
                        "destinationName", "Alpha Base Central",
                        "customerCode", customerCode,
                        "customerName", "Lifecycle Customer",
                        "shipCode", shipCode));

        Instant start = Instant.now().plusSeconds(100);
        Instant end = start.plusSeconds(60);
        Map<String, Object> noRoute = makePayload(Map.of(
                "requestRoute", false,
                "startAt", start.toString(),
                "endAt", end.toString(),
                "customerCode", customerCode,
                "shipCode", shipCode,
                "originPortId", "ALPHA-BASE-NR"));

        // when
        ResponseEntity<String> resp = postReservation(starport, noRoute);
        assertEquals(HttpStatus.CREATED, resp.getStatusCode());

        // then - rezerwacja potwierdzona bez trasy
        Long id = ((Number) parseJson(resp).get("reservationId")).longValue();
        Map<String, Object> row = jdbc.queryForMap(
                "select r.status, r.fee_charged, count(rt.id) as route_count" +
                " from reservation r left join route rt on rt.reservation_id = r.id" +
                " where r.id = ? group by r.status, r.fee_charged", id);
        assertEquals("CONFIRMED", row.get("status"));
        assertNotNull(row.get("fee_charged"));
        assertEquals(0L, row.get("route_count"));
    }
}