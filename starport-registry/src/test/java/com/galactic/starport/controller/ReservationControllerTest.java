package com.galactic.starport.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.galactic.starport.service.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Testy kontraktowe kontrolera – warstwa web bez bazy danych.
 *
 * <p>Pokrywają kontrakt HTTP: status, nagłówki, kształt JSON odpowiedzi oraz gwarancję, że
 * walidacja @Valid zatrzymuje żądanie przed dotarciem do serwisu.
 */
@WebMvcTest(ReservationController.class)
@Import(ReservationWebMapper.class)
class ReservationControllerTest {

    private static final String URL = "/api/v1/starports/DEF/reservations";
    private static final Instant FUTURE = Instant.now().plus(1, ChronoUnit.DAYS);
    private static final Instant FAR_FUTURE = Instant.now().plus(2, ChronoUnit.DAYS);

    @Autowired
    MockMvc mvc;

    @MockitoBean
    ReservationService service;

    // =========================================================================
    // Happy path
    // =========================================================================

    @Test
    void should_return_201_with_reservation_body_when_service_creates_reservation_without_route()
            throws Exception {
        // given
        given(service.reserveBay(any())).willReturn(Optional.of(aReservation(null)));

        // when
        var result = mvc.perform(post(URL)
                .contentType(MediaType.APPLICATION_JSON)
                .content(validPayload(false)));

        // then
        result.andExpect(status().isCreated())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.reservationId").value(42))
                .andExpect(jsonPath("$.starportCode").value("DEF"))
                .andExpect(jsonPath("$.bayNumber").value("BAY-1"))
                .andExpect(jsonPath("$.feeCharged").value(300))
                .andExpect(jsonPath("$.route").doesNotExist());
    }

    @Test
    void should_return_201_with_route_in_body_when_service_creates_reservation_with_route()
            throws Exception {
        // given
        var route = Route.builder()
                .routeCode("RT-1")
                .startStarportCode("ABC")
                .destinationStarportCode("DEF")
                .etaLightYears(4.2)
                .riskScore(0.3)
                .build();
        given(service.reserveBay(any())).willReturn(Optional.of(aReservation(route)));

        // when
        var result = mvc.perform(post(URL)
                .contentType(MediaType.APPLICATION_JSON)
                .content(validPayload(true)));

        // then
        result.andExpect(status().isCreated())
                .andExpect(jsonPath("$.route.routeCode").value("RT-1"))
                .andExpect(jsonPath("$.route.startStarportCode").value("ABC"))
                .andExpect(jsonPath("$.route.destinationStarportCode").value("DEF"))
                .andExpect(jsonPath("$.route.etaLightYears").value(4.2))
                .andExpect(jsonPath("$.route.riskScore").value(0.3));
    }

    @Test
    void should_return_409_when_service_returns_empty_optional() throws Exception {
        // given
        given(service.reserveBay(any())).willReturn(Optional.empty());

        // when / then
        mvc.perform(post(URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validPayload(false)))
                .andExpect(status().isConflict());
    }

    // =========================================================================
    // Service exceptions → GlobalExceptionHandler
    // =========================================================================

    @Test
    void should_return_404_with_details_when_starport_not_found() throws Exception {
        // given
        given(service.reserveBay(any())).willThrow(new StarportNotFoundException("DEF"));

        // when / then
        mvc.perform(post(URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validPayload(false)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.details").isString());
    }

    @Test
    void should_return_404_with_details_when_customer_not_found() throws Exception {
        // given
        given(service.reserveBay(any())).willThrow(new CustomerNotFoundException("C-001"));

        // when / then
        mvc.perform(post(URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validPayload(false)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.details").isString());
    }

    @Test
    void should_return_404_with_details_when_ship_not_found() throws Exception {
        // given
        given(service.reserveBay(any())).willThrow(new ShipNotFoundException("SHP-007"));

        // when / then
        mvc.perform(post(URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validPayload(false)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.details").isString());
    }

    @Test
    void should_return_409_with_details_when_no_docking_bays_available_exception_thrown()
            throws Exception {
        // given
        given(service.reserveBay(any()))
                .willThrow(new NoDockingBaysAvailableException("DEF", "SCOUT", FUTURE, FAR_FUTURE));

        // when / then
        mvc.perform(post(URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validPayload(false)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.details").isString());
    }

    @Test
    void should_return_422_with_details_when_reservation_time_is_invalid() throws Exception {
        // given
        given(service.reserveBay(any()))
                .willThrow(new InvalidReservationTimeException(FAR_FUTURE, FUTURE));

        // when / then
        mvc.perform(post(URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validPayload(false)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.details").isString());
    }

    @Test
    void should_return_500_with_error_field_when_unexpected_runtime_exception_thrown()
            throws Exception {
        // given
        given(service.reserveBay(any())).willThrow(new RuntimeException("unexpected failure"));

        // when / then
        mvc.perform(post(URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validPayload(false)))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error").isString())
                .andExpect(jsonPath("$.details").isString());
    }

    // =========================================================================
    // Bean Validation @Valid → 422 — serwis NIE może być wywołany
    // =========================================================================

    @Test
    void should_return_422_and_not_invoke_service_when_customerCode_is_null() throws Exception {
        // given
        String payload = payloadWithout("customerCode");

        // when
        mvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.customerCode").isString());

        // then
        then(service).should(never()).reserveBay(any());
    }

    @Test
    void should_return_422_and_not_invoke_service_when_customerCode_is_blank() throws Exception {
        // given
        String payload = """
                {
                  "customerCode": "   ",
                  "shipCode": "SHP-007",
                  "shipClass": "SCOUT",
                  "startAt": "%s",
                  "endAt": "%s",
                  "requestRoute": false
                }"""
                .formatted(FUTURE, FAR_FUTURE);

        // when
        mvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().isUnprocessableEntity());

        // then
        then(service).should(never()).reserveBay(any());
    }

    @Test
    void should_return_422_and_not_invoke_service_when_shipCode_is_null() throws Exception {
        // given
        String payload = payloadWithout("shipCode");

        // when
        mvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.shipCode").isString());

        // then
        then(service).should(never()).reserveBay(any());
    }

    @Test
    void should_return_422_and_not_invoke_service_when_shipClass_is_null() throws Exception {
        // given
        String payload = payloadWithout("shipClass");

        // when
        mvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.shipClass").isString());

        // then
        then(service).should(never()).reserveBay(any());
    }

    @Test
    void should_return_422_and_not_invoke_service_when_startAt_is_null() throws Exception {
        // given
        String payload = payloadWithout("startAt");

        // when
        mvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.startAt").isString());

        // then
        then(service).should(never()).reserveBay(any());
    }

    @Test
    void should_return_422_and_not_invoke_service_when_endAt_is_null() throws Exception {
        // given
        String payload = payloadWithout("endAt");

        // when
        mvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.endAt").isString());

        // then
        then(service).should(never()).reserveBay(any());
    }

    @Test
    void should_return_422_and_not_invoke_service_when_startAt_is_in_the_past() throws Exception {
        // given
        Instant past = Instant.now().minus(1, ChronoUnit.DAYS);
        String payload = """
                {
                  "customerCode": "C-001",
                  "shipCode": "SHP-007",
                  "shipClass": "SCOUT",
                  "startAt": "%s",
                  "endAt": "%s",
                  "requestRoute": false
                }"""
                .formatted(past, FAR_FUTURE);

        // when
        mvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().isUnprocessableEntity());

        // then
        then(service).should(never()).reserveBay(any());
    }

    @Test
    void should_return_422_and_not_invoke_service_when_endAt_is_in_the_past() throws Exception {
        // given
        Instant past = Instant.now().minus(1, ChronoUnit.HOURS);
        String payload = """
                {
                  "customerCode": "C-001",
                  "shipCode": "SHP-007",
                  "shipClass": "SCOUT",
                  "startAt": "%s",
                  "endAt": "%s",
                  "requestRoute": false
                }"""
                .formatted(FUTURE, past);

        // when
        mvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().isUnprocessableEntity());

        // then
        then(service).should(never()).reserveBay(any());
    }

    // =========================================================================
    // Malformed / unparseable request → 400 — serwis NIE może być wywołany
    // =========================================================================

    @Test
    void should_return_400_with_error_field_and_not_invoke_service_when_json_is_malformed()
            throws Exception {
        // when
        mvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content("{ błąd json }"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Malformed JSON"))
                .andExpect(jsonPath("$.details").isString());

        // then
        then(service).should(never()).reserveBay(any());
    }

    @Test
    void should_return_400_and_not_invoke_service_when_shipClass_enum_value_is_unknown()
            throws Exception {
        // given
        String payload = """
                {
                  "customerCode": "C-001",
                  "shipCode": "SHP-007",
                  "shipClass": "WARP_CAPABLE",
                  "startAt": "%s",
                  "endAt": "%s",
                  "requestRoute": false
                }"""
                .formatted(FUTURE, FAR_FUTURE);

        // when
        mvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().isBadRequest());

        // then
        then(service).should(never()).reserveBay(any());
    }

    // =========================================================================
    // Helpers
    // =========================================================================

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

    /**
     * Generates a valid payload with the given field removed (null/omitted) to trigger Bean
     * Validation errors.
     */
    private String payloadWithout(String field) {
        return """
                {
                  "customerCode": %s,
                  "shipCode": %s,
                  "shipClass": %s,
                  "startAt": %s,
                  "endAt": %s,
                  "requestRoute": false
                }"""
                .formatted(
                        field.equals("customerCode") ? "null" : "\"C-001\"",
                        field.equals("shipCode") ? "null" : "\"SHP-007\"",
                        field.equals("shipClass") ? "null" : "\"SCOUT\"",
                        field.equals("startAt") ? "null" : "\"" + FUTURE + "\"",
                        field.equals("endAt") ? "null" : "\"" + FAR_FUTURE + "\"");
    }
}
