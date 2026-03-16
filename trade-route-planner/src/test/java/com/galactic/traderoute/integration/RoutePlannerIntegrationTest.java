package com.galactic.traderoute.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Integration tests for the trade-route-planner microservice.
 *
 * <p>Uses a full Spring Boot application context (Eureka disabled via "test" profile) to exercise
 * the complete request pipeline: HTTP deserialization → bean validation → service → response
 * serialization.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Execution(ExecutionMode.SAME_THREAD)
class RoutePlannerIntegrationTest {

    private static final String URL = "/routes/plan";

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper objectMapper;

    // ── Happy path ────────────────────────────────────────────────────────────

    @Test
    void should_return_200_with_valid_route_response() throws Exception {
        MvcResult result = mvc.perform(post(URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request("SP-77-NARSHADDA", "SP-02-TATOOINE", "FREIGHTER_MK2", 24.0)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.routeId").isString())
                .andExpect(jsonPath("$.etaHours").isNumber())
                .andExpect(jsonPath("$.riskScore").isNumber())
                .andExpect(jsonPath("$.correlationId").isString())
                .andReturn();

        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(body.get("routeId").asText()).matches("ROUTE-[A-Z0-9]{8}");
        assertThat(body.get("etaHours").asDouble()).isBetween(18.0, 28.0);
        assertThat(body.get("riskScore").asDouble()).isBetween(0.0, 1.0);
        assertThat(body.get("correlationId").asText())
                .matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    }

    @ParameterizedTest(name = "shipClass={0} → 200 OK")
    @ValueSource(strings = {"SCOUT", "FREIGHTER", "FREIGHTER_MK2", "CRUISER", "DESTROYER"})
    void should_accept_all_ship_classes(String shipClass) throws Exception {
        mvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(request("SP-A", "SP-B", shipClass, 20.0)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.routeId").isString());
    }

    @Test
    void should_generate_unique_correlation_id_for_each_request() throws Exception {
        MvcResult r1 = mvc.perform(post(URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request("SP-A", "SP-B", "SCOUT", 10.0)))
                .andExpect(status().isOk())
                .andReturn();

        MvcResult r2 = mvc.perform(post(URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request("SP-A", "SP-B", "SCOUT", 10.0)))
                .andExpect(status().isOk())
                .andReturn();

        String corr1 = objectMapper
                .readTree(r1.getResponse().getContentAsString())
                .get("correlationId")
                .asText();
        String corr2 = objectMapper
                .readTree(r2.getResponse().getContentAsString())
                .get("correlationId")
                .asText();

        assertThat(corr1).isNotEqualTo(corr2);
    }

    // ── Business rule: insufficient fuel range ─────────────────────────────────

    @Test
    void should_return_422_with_route_rejected_when_fuel_is_below_minimum() throws Exception {
        mvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(request("SP-A", "SP-B", "SCOUT", 0.5)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("ROUTE_REJECTED"))
                .andExpect(jsonPath("$.reason").value("INSUFFICIENT_RANGE"))
                .andExpect(jsonPath("$.details").isString());
    }

    @Test
    void should_accept_request_with_exactly_minimum_fuel_range() throws Exception {
        mvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(request("SP-A", "SP-B", "SCOUT", 1.0)))
                .andExpect(status().isOk());
    }

    // ── Bean validation ────────────────────────────────────────────────────────

    @Test
    void should_return_422_when_originPortId_is_missing() throws Exception {
        String body =
                """
                {
                  "destinationPortId": "SP-DEST",
                  "shipProfile": { "class": "FREIGHTER", "fuelRangeLY": 25.0 }
                }
                """;

        mvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.originPortId").isString());
    }

    @Test
    void should_return_422_when_destinationPortId_is_missing() throws Exception {
        String body =
                """
                {
                  "originPortId": "SP-ORIGIN",
                  "shipProfile": { "class": "FREIGHTER", "fuelRangeLY": 25.0 }
                }
                """;

        mvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.destinationPortId").isString());
    }

    @Test
    void should_return_422_when_shipProfile_is_null() throws Exception {
        String body =
                """
                {
                  "originPortId": "SP-ORIGIN",
                  "destinationPortId": "SP-DEST"
                }
                """;

        mvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.shipProfile").isString());
    }

    // ── Malformed JSON ─────────────────────────────────────────────────────────

    @Test
    void should_return_400_for_malformed_json() throws Exception {
        mvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content("{ bad json }"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Malformed JSON"));
    }

    // ── Actuator health ────────────────────────────────────────────────────────

    @Test
    void actuator_health_endpoint_should_be_available() throws Exception {
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    // ── Helper ─────────────────────────────────────────────────────────────────

    private static String request(String origin, String dest, String shipClass, double fuel) {
        return """
                {
                  "originPortId": "%s",
                  "destinationPortId": "%s",
                  "shipProfile": { "class": "%s", "fuelRangeLY": %s }
                }
                """
                .formatted(origin, dest, shipClass, fuel);
    }
}
