package com.galactic.starport.service.outbox

import com.galactic.starport.BaseAcceptanceSpec
import org.springframework.http.HttpStatus

import java.math.BigDecimal
import java.time.Instant

class OutboxAppenderTest extends BaseAcceptanceSpec {

    private static final String STARPORT = "OUTBOX-DEF"

    def setup() {
        purgeAndReset()
        jdbc.execute("TRUNCATE TABLE event_outbox RESTART IDENTITY CASCADE")

        seedDefaultReservationFixture(STARPORT, [destinationName: "Outbox Starport"])
    }

    def "publishReservationConfirmedEvent – zapisuje event_outbox z payload i header contentType (z trasą)"() {
        given:
        def start = Instant.now().plusSeconds(3600)
        def end = start.plusSeconds(3600)

        Map body = makePayload([
                requestRoute: true,
                startAt      : start.toString(),
                endAt        : end.toString()
        ])

        when: "Tworzymy rezerwację przez API (z potwierdzeniem + trasą)"
        def resp = postReservation(STARPORT, body)

        then:
        resp.statusCode == HttpStatus.CREATED

        and: "Mamy rezerwację CONFIRMED"
        Long reservationId = jdbc.queryForObject(
                "select id from reservation order by id desc limit 1",
                Long.class
        )
        assert jdbc.queryForObject("select status from reservation where id = ?", String.class, reservationId) == "CONFIRMED"

        and: "W outboxie jest dokładnie 1 event PENDING typu ReservationConfirmed"
        assert jdbc.queryForObject("select count(*) from event_outbox", Integer.class) == 1

        Map e = latestOutboxEvent()
        assert e.event_type == "ReservationConfirmed"
        assert e.status == "PENDING"
        // binding ma domyślną wartość z @Value; jeśli kiedyś zmienisz property, test wskaże regresję
        assert e.binding == "reservations-out"
        assert e.message_key == String.valueOf(reservationId)

        and: "Payload zawiera kluczowe dane biznesowe (w tym routeCode)"
        assert e.payload_reservation_id == String.valueOf(reservationId)
        assert e.payload_status == "CONFIRMED"
        assert e.payload_starport_code == STARPORT
        assert e.payload_bay_label == "1"
        assert e.payload_customer_code == "CUST-001"
        assert e.payload_ship_code == "SS-Enterprise-01"

        and: "routeCode z payload odpowiada temu, co zapisano w tabeli route"
        String routeCodeFromDb = jdbc.queryForObject(
                "select route_code from route where reservation_id = ?",
                String.class,
                reservationId
        )
        assert routeCodeFromDb != null
        assert e.payload_route_code == routeCodeFromDb

        and: "feeCharged z payload odpowiada fee_charged z reservation"
        BigDecimal feeFromDb = jdbc.queryForObject(
                "select fee_charged from reservation where id = ?",
                BigDecimal.class,
                reservationId
        )
        assert feeFromDb != null
        assert new BigDecimal(e.payload_fee_charged) == feeFromDb
    }

    def "publishReservationConfirmedEvent – zapisuje event_outbox także bez trasy (routeCode null)"() {
        given:
        Map body = makePayload([
                requestRoute: false
        ])

        when: "Tworzymy rezerwację przez API (potwierdzenie bez trasy)"
        def resp = postReservation(STARPORT, body)

        then:
        resp.statusCode == HttpStatus.CREATED

        and:
        Long reservationId = jdbc.queryForObject(
                "select id from reservation order by id desc limit 1",
                Long.class
        )
        assert jdbc.queryForObject("select count(*) from route where reservation_id = ?", Integer.class, reservationId) == 0

        and:
        assert jdbc.queryForObject("select count(*) from event_outbox", Integer.class) == 1

        Map e = latestOutboxEvent()
        assert e.event_type == "ReservationConfirmed"
        assert e.status == "PENDING"
        assert e.binding == "reservations-out"
        assert e.message_key == String.valueOf(reservationId)

        and: "W payload routeCode jest null (lub brak klucza; w obu przypadkach ->> zwraca null)"
        assert e.payload_route_code == null

        and: "Pozostałe dane biznesowe są obecne"
        assert e.payload_reservation_id == String.valueOf(reservationId)
        assert e.payload_status == "CONFIRMED"
        assert e.payload_starport_code == STARPORT
        assert e.payload_customer_code == "CUST-001"
        assert e.payload_ship_code == "SS-Enterprise-01"
    }

    private Map latestOutboxEvent() {
        jdbc.queryForMap("""
            select
                id,
                event_type,
                binding,
                message_key,
                status,
                payload_json ->> 'reservationId'  as payload_reservation_id,
                payload_json ->> 'status'         as payload_status,
                payload_json ->> 'starportCode'   as payload_starport_code,
                payload_json ->> 'dockingBayLabel' as payload_bay_label,
                payload_json ->> 'customerCode'   as payload_customer_code,
                payload_json ->> 'shipCode'       as payload_ship_code,
                payload_json ->> 'routeCode'      as payload_route_code,
                payload_json ->> 'feeCharged'     as payload_fee_charged,
                headers_json ->> 'contentType'    as header_content_type
            from event_outbox
            order by id desc
            limit 1
        """)
    }
}
