package com.galactic.starport.service.outbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.galactic.starport.BaseAcceptanceTest;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceAccessMode;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

@ResourceLock(value = "DB_TRUNCATE", mode = ResourceAccessMode.READ_WRITE)
class OutboxAppenderRepositoryTest extends BaseAcceptanceTest {

    private static final String STARPORT = "OUTBOX-DEF";

    @BeforeEach
    void setup() {
        purgeAndReset();
        jdbc.execute("TRUNCATE TABLE event_outbox RESTART IDENTITY CASCADE");
        seedDefaultReservationFixture(STARPORT, Map.of("destinationName", "Outbox Starport"));
    }

    @Test
    void publishReservationConfirmedEventSavesOutboxWithPayloadAndHeadersWithRoute() {
        // given
        Instant start = Instant.now().plusSeconds(3600);
        Instant end = start.plusSeconds(3600);
        Map<String, Object> body =
                makePayload(Map.of("requestRoute", true, "startAt", start.toString(), "endAt", end.toString()));

        // when - Tworzymy rezerwację przez API (z potwierdzeniem + trasą)
        ResponseEntity<String> resp = postReservation(STARPORT, body);

        // then
        assertEquals(HttpStatus.CREATED, resp.getStatusCode());

        // and - Mamy rezerwację CONFIRMED
        Long reservationId =
                jdbc.queryForObject("select id from reservation order by id desc limit 1", Long.class);
        assertEquals(
                "CONFIRMED",
                jdbc.queryForObject("select status from reservation where id = ?", String.class, reservationId));

        // and - W outboxie jest dokładnie 1 event PENDING typu ReservationConfirmed
        assertEquals(1, jdbc.queryForObject("select count(*) from event_outbox", Integer.class));

        Map<String, Object> e = latestOutboxEvent();
        assertEquals("ReservationConfirmed", e.get("event_type"));
        assertEquals("PENDING", e.get("status"));
        assertEquals("reservationCreated-out-0", e.get("binding"));
        assertEquals(String.valueOf(reservationId), e.get("message_key"));

        // and - Payload zawiera kluczowe dane biznesowe (w tym routeCode)
        assertEquals(String.valueOf(reservationId), e.get("payload_reservation_id"));
        assertEquals("CONFIRMED", e.get("payload_status"));
        assertEquals(STARPORT, e.get("payload_starport_code"));
        assertEquals("1", e.get("payload_bay_label"));
        assertEquals("CUST-001", e.get("payload_customer_code"));
        assertEquals("SS-Enterprise-01", e.get("payload_ship_code"));

        // and - routeCode z payload odpowiada temu, co zapisano w tabeli route
        String routeCodeFromDb = jdbc.queryForObject(
                "select route_code from route where reservation_id = ?", String.class, reservationId);
        assertNotNull(routeCodeFromDb);
        assertEquals(routeCodeFromDb, e.get("payload_route_code"));

        // and - feeCharged z payload odpowiada fee_charged z reservation
        BigDecimal feeFromDb = jdbc.queryForObject(
                "select fee_charged from reservation where id = ?", BigDecimal.class, reservationId);
        assertNotNull(feeFromDb);
        assertEquals(0, feeFromDb.compareTo(new BigDecimal((String) e.get("payload_fee_charged"))));
    }

    @Test
    void publishReservationConfirmedEventSavesOutboxWithoutRoute() {
        // given
        Map<String, Object> body = makePayload(Map.of("requestRoute", false));

        // when - Tworzymy rezerwację przez API (potwierdzenie bez trasy)
        ResponseEntity<String> resp = postReservation(STARPORT, body);

        // then
        assertEquals(HttpStatus.CREATED, resp.getStatusCode());

        Long reservationId =
                jdbc.queryForObject("select id from reservation order by id desc limit 1", Long.class);
        assertEquals(
                0,
                jdbc.queryForObject(
                        "select count(*) from route where reservation_id = ?", Integer.class, reservationId));

        assertEquals(1, jdbc.queryForObject("select count(*) from event_outbox", Integer.class));

        Map<String, Object> e = latestOutboxEvent();
        assertEquals("ReservationConfirmed", e.get("event_type"));
        assertEquals("PENDING", e.get("status"));
        assertEquals("reservationCreated-out-0", e.get("binding"));
        assertEquals(String.valueOf(reservationId), e.get("message_key"));

        // and - W payload routeCode jest null
        assertNull(e.get("payload_route_code"));

        // and - Pozostałe dane biznesowe są obecne
        assertEquals(String.valueOf(reservationId), e.get("payload_reservation_id"));
        assertEquals("CONFIRMED", e.get("payload_status"));
        assertEquals(STARPORT, e.get("payload_starport_code"));
        assertEquals("CUST-001", e.get("payload_customer_code"));
        assertEquals("SS-Enterprise-01", e.get("payload_ship_code"));
    }

    private Map<String, Object> latestOutboxEvent() {
        return jdbc.queryForMap(
                """
                select
                    id,
                    event_type,
                    binding,
                    message_key,
                    status,
                    payload_json ->> 'reservationId'   as payload_reservation_id,
                    payload_json ->> 'status'          as payload_status,
                    payload_json ->> 'starportCode'    as payload_starport_code,
                    payload_json ->> 'dockingBayLabel' as payload_bay_label,
                    payload_json ->> 'customerCode'    as payload_customer_code,
                    payload_json ->> 'shipCode'        as payload_ship_code,
                    payload_json ->> 'routeCode'       as payload_route_code,
                    payload_json ->> 'feeCharged'      as payload_fee_charged,
                    headers_json ->> 'contentType'     as header_content_type
                from event_outbox
                order by id desc
                limit 1
                """);
    }
}
