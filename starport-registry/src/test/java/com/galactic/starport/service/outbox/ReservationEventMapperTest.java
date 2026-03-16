package com.galactic.starport.service.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.galactic.starport.service.Customer;
import com.galactic.starport.service.DockingBay;
import com.galactic.starport.service.Reservation;
import com.galactic.starport.service.Route;
import com.galactic.starport.service.Ship;
import com.galactic.starport.service.Starport;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

@Execution(ExecutionMode.CONCURRENT)
class ReservationEventMapperTest {

    private ReservationEventMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new ReservationEventMapper();
    }

    @Test
    void should_map_full_reservation_to_payload() {

        Instant start = Instant.parse("2027-01-01T08:00:00Z");
        Instant end = Instant.parse("2027-01-01T09:00:00Z");
        Reservation reservation = Reservation.builder()
                .id(42L)
                .status(Reservation.ReservationStatus.CONFIRMED)
                .starport(Starport.builder().code("DEF").build())
                .dockingBay(DockingBay.builder().bayLabel("BAY-1").build())
                .customer(Customer.builder().customerCode("CUST-001").build())
                .ship(Ship.builder().shipCode("SS-007").build())
                .route(Route.builder().routeCode("RT-1").build())
                .startAt(start)
                .endAt(end)
                .feeCharged(BigDecimal.valueOf(300))
                .build();

        ReservationEventPayload payload = mapper.toPayload(reservation);

        assertThat(payload.getReservationId()).isEqualTo(42L);
        assertThat(payload.getStatus()).isEqualTo("CONFIRMED");
        assertThat(payload.getStarportCode()).isEqualTo("DEF");
        assertThat(payload.getDockingBayLabel()).isEqualTo("BAY-1");
        assertThat(payload.getCustomerCode()).isEqualTo("CUST-001");
        assertThat(payload.getShipCode()).isEqualTo("SS-007");
        assertThat(payload.getRouteCode()).isEqualTo("RT-1");
        assertThat(payload.getStartAt()).isEqualTo(start);
        assertThat(payload.getEndAt()).isEqualTo(end);
        assertThat(payload.getFeeCharged()).isEqualByComparingTo(BigDecimal.valueOf(300));
    }

    @Test
    void should_map_reservation_without_optional_fields_to_payload() {

        Reservation reservation = Reservation.builder()
                .id(10L)
                .status(Reservation.ReservationStatus.HOLD)
                .starport(null)
                .dockingBay(null)
                .customer(null)
                .ship(null)
                .route(null)
                .build();

        ReservationEventPayload payload = mapper.toPayload(reservation);

        assertThat(payload.getReservationId()).isEqualTo(10L);
        assertThat(payload.getStatus()).isEqualTo("HOLD");
        assertThat(payload.getStarportCode()).isNull();
        assertThat(payload.getDockingBayLabel()).isNull();
        assertThat(payload.getCustomerCode()).isNull();
        assertThat(payload.getShipCode()).isNull();
        assertThat(payload.getRouteCode()).isNull();
    }
}
