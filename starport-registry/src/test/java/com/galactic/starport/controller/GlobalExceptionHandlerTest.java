package com.galactic.starport.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.galactic.starport.service.CustomerNotFoundException;
import com.galactic.starport.service.InvalidReservationTimeException;
import com.galactic.starport.service.NoDockingBaysAvailableException;
import com.galactic.starport.service.ShipNotFoundException;
import com.galactic.starport.service.StarportNotFoundException;
import com.galactic.starport.service.routeplanner.RouteUnavailableException;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

@Execution(ExecutionMode.CONCURRENT)
class GlobalExceptionHandlerTest {

    private GlobalExceptionHandler handler;

    @BeforeEach
    void setUp() {
        handler = new GlobalExceptionHandler();
    }

    @Test
    void should_return_404_for_starport_not_found() {
        ResponseEntity<Map<String, String>> response =
                handler.handle(new StarportNotFoundException("SP-X"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).containsKey("details");
    }

    @Test
    void should_return_404_for_customer_not_found() {
        ResponseEntity<Map<String, String>> response =
                handler.handle(new CustomerNotFoundException("CUST-X"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void should_return_404_for_ship_not_found() {
        ResponseEntity<Map<String, String>> response =
                handler.handle(new ShipNotFoundException("SHIP-X"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void should_return_409_for_no_docking_bays() {
        ResponseEntity<Map<String, String>> response =
                handler.handle(new NoDockingBaysAvailableException("SP-X", "SCOUT",
                        Instant.now(), Instant.now().plusSeconds(3600)));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void should_return_422_for_invalid_reservation_time() {
        ResponseEntity<Map<String, String>> response =
                handler.handle(new InvalidReservationTimeException(
                        Instant.parse("2026-01-02T00:00:00Z"), Instant.parse("2026-01-01T00:00:00Z")));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @Test
    void should_return_409_for_route_unavailable() {
        ResponseEntity<Map<String, String>> response =
                handler.handle(new RouteUnavailableException("SP-A", "SP-B"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).containsEntry("error", "ROUTE_UNAVAILABLE");
    }

    @Test
    void should_return_500_without_leaking_details_for_runtime_exception() {
        ResponseEntity<Map<String, String>> response =
                handler.handleRuntime(new RuntimeException("sensitive SQL error details"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody()).containsEntry("error", "Internal server error");
        assertThat(response.getBody()).doesNotContainKey("details");
    }

    @Test
    void should_return_500_safely_when_runtime_exception_has_null_message() {
        ResponseEntity<Map<String, String>> response =
                handler.handleRuntime(new RuntimeException());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody()).containsEntry("error", "Internal server error");
    }
}
