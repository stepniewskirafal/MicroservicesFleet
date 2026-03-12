package com.galactic.starport.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.galactic.starport.service.CustomerNotFoundException;
import com.galactic.starport.service.DockingBay;
import com.galactic.starport.service.InvalidReservationTimeException;
import com.galactic.starport.service.NoDockingBaysAvailableException;
import com.galactic.starport.service.Reservation;
import com.galactic.starport.service.ReservationService;
import com.galactic.starport.service.Route;
import com.galactic.starport.service.ShipNotFoundException;
import com.galactic.starport.service.StarportNotFoundException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Contract tests for the Reservation API.
 *
 */
@WebMvcTest(ReservationController.class)
@Import(ReservationWebMapper.class)
@Execution(ExecutionMode.SAME_THREAD)
class ReservationApiContractTest {

    private static final String URL = "/api/v1/starports/{code}/reservations";
    private static final Instant FUTURE = Instant.now().plus(1, ChronoUnit.DAYS);
    private static final Instant FAR_FUTURE = Instant.now().plus(2, ChronoUnit.DAYS);

    @Autowired
    MockMvc mvc;

    @MockitoBean
    ReservationService service;

    @Test
    void contract_create_reservation_with_route_returns_201_and_complete_body() throws Exception {
        var route = Route.builder()
                .routeCode("RT-42")
                .startStarportCode("ABC")
                .destinationStarportCode("DEF")
                .etaHours(4.2)
                .riskScore(0.15)
                .build();
        given(service.reserveBay(any())).willReturn(Optional.of(aReservation(route)));

        mvc.perform(post(URL, "DEF").contentType(MediaType.APPLICATION_JSON).content(validPayload(true)))
                .andExpect(status().isCreated())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.reservationId").isNumber())
                .andExpect(jsonPath("$.starportCode").isString())
                .andExpect(jsonPath("$.bayNumber").isString())
                .andExpect(jsonPath("$.feeCharged").isNumber())
                .andExpect(jsonPath("$.startAt").isString())
                .andExpect(jsonPath("$.endAt").isString())
                .andExpect(jsonPath("$.route").isMap())
                .andExpect(jsonPath("$.route.routeCode").isString())
                .andExpect(jsonPath("$.route.startStarportCode").isString())
                .andExpect(jsonPath("$.route.destinationStarportCode").isString())
                .andExpect(jsonPath("$.route.etaHours").isNumber())
                .andExpect(jsonPath("$.route.riskScore").isNumber());
    }

    @Test
    void contract_create_reservation_without_route_returns_201_and_no_route_field() throws Exception {
        given(service.reserveBay(any())).willReturn(Optional.of(aReservation(null)));

        mvc.perform(post(URL, "DEF").contentType(MediaType.APPLICATION_JSON).content(validPayload(false)))
                .andExpect(status().isCreated())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.reservationId").isNumber())
                .andExpect(jsonPath("$.starportCode").value("DEF"))
                .andExpect(jsonPath("$.route").doesNotExist());
    }

    @Test
    void contract_starport_not_found_returns_404_with_details_field() throws Exception {
        given(service.reserveBay(any())).willThrow(new StarportNotFoundException("DEF"));

        mvc.perform(post(URL, "DEF").contentType(MediaType.APPLICATION_JSON).content(validPayload(false)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.details").isString())
                .andExpect(jsonPath("$.details").isNotEmpty());
    }

    @Test
    void contract_customer_not_found_returns_404_with_details_field() throws Exception {
        given(service.reserveBay(any())).willThrow(new CustomerNotFoundException("CUST-999"));

        mvc.perform(post(URL, "DEF").contentType(MediaType.APPLICATION_JSON).content(validPayload(false)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.details").isString());
    }

    @Test
    void contract_ship_not_found_returns_404_with_details_field() throws Exception {
        given(service.reserveBay(any())).willThrow(new ShipNotFoundException("SHP-999"));

        mvc.perform(post(URL, "DEF").contentType(MediaType.APPLICATION_JSON).content(validPayload(false)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.details").isString());
    }

    @Test
    void contract_no_docking_bays_available_returns_409_with_details_field() throws Exception {
        given(service.reserveBay(any()))
                .willThrow(new NoDockingBaysAvailableException("DEF", "SCOUT", FUTURE, FAR_FUTURE));

        mvc.perform(post(URL, "DEF").contentType(MediaType.APPLICATION_JSON).content(validPayload(false)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.details").isString());
    }

    @Test
    void contract_invalid_reservation_time_returns_422_with_details_field() throws Exception {
        given(service.reserveBay(any())).willThrow(new InvalidReservationTimeException(FAR_FUTURE, FUTURE));

        mvc.perform(post(URL, "DEF").contentType(MediaType.APPLICATION_JSON).content(validPayload(false)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.details").isString());
    }

    @Test
    void contract_malformed_json_returns_400_with_error_and_details_fields() throws Exception {
        mvc.perform(post(URL, "DEF").contentType(MediaType.APPLICATION_JSON).content("{ invalid json }"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Malformed JSON"))
                .andExpect(jsonPath("$.details").isString());
    }

    @Test
    void contract_missing_required_field_returns_422_with_field_name_as_key() throws Exception {
        String payload =
                """
                {
                  "shipCode": "SHP-007",
                  "shipClass": "SCOUT",
                  "startAt": "%s",
                  "endAt": "%s",
                  "requestRoute": false
                }"""
                        .formatted(FUTURE, FAR_FUTURE);

        mvc.perform(post(URL, "DEF").contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.customerCode").isString());
    }

    @Test
    void contract_conflict_returns_409_when_service_returns_empty() throws Exception {
        given(service.reserveBay(any())).willReturn(Optional.empty());

        mvc.perform(post(URL, "DEF").contentType(MediaType.APPLICATION_JSON).content(validPayload(false)))
                .andExpect(status().isConflict());
    }

    private Reservation aReservation(Route route) {
        return Reservation.builder()
                .id(42L)
                .dockingBay(DockingBay.builder().bayLabel("BAY-1").build())
                .startAt(FUTURE)
                .endAt(FAR_FUTURE)
                .feeCharged(BigDecimal.valueOf(300))
                .route(route)
                .build();
    }

    private String validPayload(boolean requestRoute) {
        return """
                {
                  "customerCode": "C-001",
                  "shipCode": "SHP-007",
                  "shipClass": "SCOUT",
                  "startAt": "%s",
                  "endAt": "%s",
                  "requestRoute": %b
                }"""
                .formatted(FUTURE, FAR_FUTURE, requestRoute);
    }
}
