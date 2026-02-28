package com.galactic.starport.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.galactic.starport.service.DockingBay;
import com.galactic.starport.service.ReserveBayCommand;
import com.galactic.starport.service.Reservation;
import com.galactic.starport.service.Route;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

@Execution(ExecutionMode.CONCURRENT)
class ReservationWebMapperTest {

    private ReservationWebMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new ReservationWebMapper();
    }

    @Test
    void should_map_request_to_command_with_all_fields() {
        Instant start = Instant.parse("2027-01-01T08:00:00Z");
        Instant end = Instant.parse("2027-01-01T09:00:00Z");
        ReservationCreateRequest req = new ReservationCreateRequest(
                "CUST-001", "SHP-007", ReservationCreateRequest.ShipClass.SCOUT, start, end, true, "ALPHA-BASE");

        ReserveBayCommand cmd = mapper.toCommand("DEF", req);

        assertThat(cmd.destinationStarportCode()).isEqualTo("DEF");
        assertThat(cmd.startStarportCode()).isEqualTo("ALPHA-BASE");
        assertThat(cmd.customerCode()).isEqualTo("CUST-001");
        assertThat(cmd.shipCode()).isEqualTo("SHP-007");
        assertThat(cmd.shipClass()).isEqualTo(ReserveBayCommand.ShipClass.SCOUT);
        assertThat(cmd.startAt()).isEqualTo(start);
        assertThat(cmd.endAt()).isEqualTo(end);
        assertThat(cmd.requestRoute()).isTrue();
    }

    @Test
    void should_map_request_without_route_to_command() {
        // given
        Instant start = Instant.parse("2027-01-01T08:00:00Z");
        Instant end = Instant.parse("2027-01-01T09:00:00Z");
        ReservationCreateRequest req = new ReservationCreateRequest(
                "CUST-001", "SHP-007", ReservationCreateRequest.ShipClass.FREIGHTER, start, end, false, null);

        // when
        ReserveBayCommand cmd = mapper.toCommand("OMEGA", req);

        // then
        assertThat(cmd.destinationStarportCode()).isEqualTo("OMEGA");
        assertThat(cmd.startStarportCode()).isNull();
        assertThat(cmd.requestRoute()).isFalse();
        assertThat(cmd.shipClass()).isEqualTo(ReserveBayCommand.ShipClass.FREIGHTER);
    }

    @Test
    void should_map_reservation_to_response_with_route() {
        // given
        Instant start = Instant.parse("2027-01-01T08:00:00Z");
        Instant end = Instant.parse("2027-01-01T09:00:00Z");
        Route route = Route.builder()
                .routeCode("RT-1")
                .startStarportCode("ABC")
                .destinationStarportCode("DEF")
                .etaLightYears(4.2)
                .riskScore(0.3)
                .build();
        Reservation reservation = Reservation.builder()
                .id(42L)
                .dockingBay(DockingBay.builder().bayLabel("BAY-1").build())
                .startAt(start)
                .endAt(end)
                .feeCharged(BigDecimal.valueOf(300))
                .route(route)
                .build();

        // when
        ReservationResponse resp = mapper.toResponse("DEF", reservation);

        // then
        assertThat(resp.reservationId()).isEqualTo(42L);
        assertThat(resp.starportCode()).isEqualTo("DEF");
        assertThat(resp.bayNumber()).isEqualTo("BAY-1");
        assertThat(resp.startAt()).isEqualTo(start);
        assertThat(resp.endAt()).isEqualTo(end);
        assertThat(resp.feeCharged()).isEqualByComparingTo(BigDecimal.valueOf(300));
        assertThat(resp.route()).isNotNull();
        assertThat(resp.route().routeCode()).isEqualTo("RT-1");
        assertThat(resp.route().startStarportCode()).isEqualTo("ABC");
        assertThat(resp.route().destinationStarportCode()).isEqualTo("DEF");
        assertThat(resp.route().etaLightYears()).isEqualTo(4.2);
        assertThat(resp.route().riskScore()).isEqualTo(0.3);
    }

    @Test
    void should_map_reservation_to_response_without_route() {
        // given
        Reservation reservation = Reservation.builder()
                .id(10L)
                .dockingBay(DockingBay.builder().bayLabel("BAY-3").build())
                .startAt(Instant.parse("2027-01-01T08:00:00Z"))
                .endAt(Instant.parse("2027-01-01T09:00:00Z"))
                .feeCharged(BigDecimal.valueOf(50))
                .route(null)
                .build();

        // when
        ReservationResponse resp = mapper.toResponse("OMEGA", reservation);

        // then
        assertThat(resp.reservationId()).isEqualTo(10L);
        assertThat(resp.starportCode()).isEqualTo("OMEGA");
        assertThat(resp.route()).isNull();
    }
}
