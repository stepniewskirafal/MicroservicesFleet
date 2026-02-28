package com.galactic.starport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceAccessMode;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * ATDD: obsługa błędów i cykl życia rezerwacji – scenariusze integracyjne.
 */
@ResourceLock(value = "DB_TRUNCATE", mode = ResourceAccessMode.READ)
class ReservationLifecycleE2ETest extends BaseAcceptanceTest {

    record ReservationSnapshot(String status, BigDecimal feeCharged, int routeCount) {}

    private ReservationSnapshot fetchSnapshot(long reservationId) {
        return jdbc.queryForObject(
                "select r.status, r.fee_charged, count(rt.id) as route_count" +
                " from reservation r left join route rt on rt.reservation_id = r.id" +
                " where r.id = ? group by r.status, r.fee_charged",
                (rs, rowNum) -> new ReservationSnapshot(
                        rs.getString("status"),
                        rs.getBigDecimal("fee_charged"),
                        rs.getInt("route_count")),
                reservationId);
    }

    @Test
    void lifecycleCreateAndConfirmReservationWithRoute() throws Exception {
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

        
        ResponseEntity<String> resp = postReservation(starport, withRoute);

        assertEquals(HttpStatus.CREATED, resp.getStatusCode());
        Long id = ((Number) parseJson(resp).get("reservationId")).longValue();
        ReservationSnapshot snapshot = fetchSnapshot(id);
        assertEquals("CONFIRMED", snapshot.status());
        assertNotNull(snapshot.feeCharged());
        assertEquals(1, snapshot.routeCount());
    }

    @Test
    void lifecycleCreateAndConfirmReservationWithoutRoute() throws Exception {
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

        
        ResponseEntity<String> resp = postReservation(starport, noRoute);
        assertEquals(HttpStatus.CREATED, resp.getStatusCode());

        Long id = ((Number) parseJson(resp).get("reservationId")).longValue();
        ReservationSnapshot snapshot = fetchSnapshot(id);
        assertEquals("CONFIRMED", snapshot.status());
        assertNotNull(snapshot.feeCharged());
        assertEquals(0, snapshot.routeCount());
    }
}
