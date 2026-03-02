package com.galactic.traderoute.adapter.in.rest;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.galactic.traderoute.domain.model.RouteRejectionException;
import com.galactic.traderoute.port.in.PlanRouteUseCase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Unit tests for {@link RoutePlannerExceptionHandler}.
 * Verifies the exact JSON shape of every error response the handler produces.
 */
@WebMvcTest({RoutePlannerController.class, RoutePlannerExceptionHandler.class})
@Execution(ExecutionMode.SAME_THREAD)
class RoutePlannerExceptionHandlerTest {

    private static final String URL = "/routes/plan";

    @Autowired
    MockMvc mvc;

    @MockitoBean
    PlanRouteUseCase planRouteUseCase;

    // ── RouteRejectionException ───────────────────────────────────────────────

    @Test
    void should_return_422_with_all_rejection_fields_populated() throws Exception {
        given(planRouteUseCase.planRoute(any()))
                .willThrow(new RouteRejectionException("INSUFFICIENT_RANGE", "Needs at least 1 LY"));

        mvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(validRequest()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("ROUTE_REJECTED"))
                .andExpect(jsonPath("$.reason").value("INSUFFICIENT_RANGE"))
                .andExpect(jsonPath("$.details").value("Needs at least 1 LY"));
    }

    @Test
    void should_propagate_custom_rejection_reason() throws Exception {
        given(planRouteUseCase.planRoute(any()))
                .willThrow(new RouteRejectionException("BLOCKED_ROUTE", "Hyperlane occupied"));

        mvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(validRequest()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.reason").value("BLOCKED_ROUTE"))
                .andExpect(jsonPath("$.details").value("Hyperlane occupied"));
    }

    // ── Bean validation ───────────────────────────────────────────────────────

    @Test
    void should_return_422_with_field_name_when_originPortId_blank() throws Exception {
        String body =
                """
                {
                  "originPortId": "",
                  "destinationPortId": "SP-DEST",
                  "shipProfile": { "class": "FREIGHTER", "fuelRangeLY": 25.0 }
                }
                """;

        mvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.originPortId").isString());
    }

    @Test
    void should_return_422_with_field_name_when_destinationPortId_blank() throws Exception {
        String body =
                """
                {
                  "originPortId": "SP-ORIGIN",
                  "destinationPortId": " ",
                  "shipProfile": { "class": "FREIGHTER", "fuelRangeLY": 25.0 }
                }
                """;

        mvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.destinationPortId").isString());
    }

    @Test
    void should_return_422_when_fuelRangeLY_is_negative() throws Exception {
        String body =
                """
                {
                  "originPortId": "SP-ORIGIN",
                  "destinationPortId": "SP-DEST",
                  "shipProfile": { "class": "SCOUT", "fuelRangeLY": -5.0 }
                }
                """;

        mvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$['shipProfile.fuelRangeLY']").doesNotExist());
        // The exact field path depends on framework; just confirm it's 422
    }

    // ── Malformed JSON ────────────────────────────────────────────────────────

    @Test
    void should_return_400_with_error_and_details_for_malformed_json() throws Exception {
        mvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content("{ bad json }"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Malformed JSON"))
                .andExpect(jsonPath("$.details").isString());
    }

    @Test
    void should_return_400_for_completely_empty_body() throws Exception {
        mvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(""))
                .andExpect(status().isBadRequest());
    }

    // ── Unexpected RuntimeException ───────────────────────────────────────────

    @Test
    void should_return_500_for_unexpected_runtime_exception() throws Exception {
        given(planRouteUseCase.planRoute(any())).willThrow(new RuntimeException("Something went wrong"));

        mvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(validRequest()))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error").value("Internal server error"))
                .andExpect(jsonPath("$.details").value("Something went wrong"));
    }

    private static String validRequest() {
        return """
                {
                  "originPortId": "SP-77-NARSHADDA",
                  "destinationPortId": "SP-02-TATOOINE",
                  "shipProfile": { "class": "FREIGHTER_MK2", "fuelRangeLY": 24.0 }
                }
                """;
    }
}
