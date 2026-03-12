package com.galactic.traderoute.adapter.in.rest;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.galactic.traderoute.domain.model.PlannedRoute;
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

@WebMvcTest({RoutePlannerController.class, RoutePlannerExceptionHandler.class})
@Execution(ExecutionMode.SAME_THREAD)
class RoutePlannerControllerTest {

    private static final String URL = "/routes/plan";

    @Autowired
    MockMvc mvc;

    @MockitoBean
    PlanRouteUseCase planRouteUseCase;

    @Test
    void should_return_200_with_route_when_route_planned() throws Exception {
        given(planRouteUseCase.planRoute(any()))
                .willReturn(PlannedRoute.builder()
                        .routeId("ROUTE-ABC123")
                        .etaHours(18.7)
                        .riskScore(0.32)
                        .build());

        mvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(validRequest()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.routeId").value("ROUTE-ABC123"))
                .andExpect(jsonPath("$.etaHours").value(18.7))
                .andExpect(jsonPath("$.riskScore").value(0.32))
                .andExpect(jsonPath("$.correlationId").isString());
    }

    @Test
    void should_return_422_when_route_rejected() throws Exception {
        given(planRouteUseCase.planRoute(any()))
                .willThrow(new RouteRejectionException("INSUFFICIENT_RANGE", "Fuel range too low"));

        mvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(validRequest()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("ROUTE_REJECTED"))
                .andExpect(jsonPath("$.reason").value("INSUFFICIENT_RANGE"));
    }

    @Test
    void should_return_422_when_originPortId_missing() throws Exception {
        String body =
                """
                {
                  "destinationPortId": "SP-DEST",
                  "shipProfile": { "class": "FREIGHTER", "fuelRangeLY": 25.0 }
                }
                """;

        mvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void should_return_422_when_shipProfile_missing() throws Exception {
        String body =
                """
                {
                  "originPortId": "SP-ORIGIN",
                  "destinationPortId": "SP-DEST"
                }
                """;

        mvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void should_return_400_when_json_is_malformed() throws Exception {
        mvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content("{ bad json }"))
                .andExpect(status().isBadRequest());
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
