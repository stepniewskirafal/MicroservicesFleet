package com.galactic.starport;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.parallel.ResourceAccessMode;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;

@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ResourceLock(value = "WIREMOCK", mode = ResourceAccessMode.READ)
public abstract class BaseAcceptanceTest {

    public static final WireMockServer wireMock;

    @ServiceConnection
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("app")
            .withUsername("test")
            .withPassword("test");

    static {
        pg.start();
        wireMock = new WireMockServer(WireMockConfiguration.wireMockConfig().port(8089));
        wireMock.start();
        stubDefaultRoutePlan();
    }

    static void stubDefaultRoutePlan() {
        wireMock.stubFor(WireMock.post(WireMock.urlEqualTo("/routes/plan"))
                .willReturn(WireMock.aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(
                                """
                                {
                                  "routeId": "ROUTE-TEST-1234",
                                  "etaHours": 18.7,
                                  "riskScore": 0.32,
                                  "correlationId": "test-correlation-id"
                                }
                                """)));
    }

    @AfterEach
    void resetWireMock() {
        wireMock.resetAll();
        stubDefaultRoutePlan();
    }

    @LocalServerPort
    int port;

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected TestRestTemplate rest;

    @Autowired
    protected ObjectMapper objectMapper;

    // ----------------------------- HTTP -------------------------------------

    protected String api(String path) {
        return "http://localhost:" + port + path;
    }

    protected ResponseEntity<String> postReservation(String starportCode, Map<String, Object> body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.postForEntity(
                api("/api/v1/starports/" + starportCode + "/reservations"),
                new HttpEntity<>(body, headers),
                String.class);
    }

    protected Map<?, ?> parseJson(ResponseEntity<String> resp) throws Exception {
        return objectMapper.readValue(resp.getBody(), Map.class);
    }

    // ---------------------------- DATA DSL ----------------------------------

    protected void purgeAndReset() {
        truncateDomainTables();
    }

    protected Map<String, Long> seedDefaultReservationFixture(String destinationCode, Map<String, Object> overrides) {
        Map<String, Object> config = new HashMap<>();
        config.put("originCode", "ALPHA-BASE");
        config.put("originName", "Alpha Base");
        config.put("originDescription", null);
        config.put("destinationCode", destinationCode);
        config.put("destinationName", destinationCode + " Starport");
        config.put("destinationDescription", null);
        config.put("destinationBayLabel", "1");
        config.put("destinationBayShipClass", "SCOUT");
        config.put("destinationBayStatus", "AVAILABLE");
        config.put("customerCode", "CUST-001");
        config.put("customerName", "Default Customer");
        config.put("shipCode", "SS-Enterprise-01");
        config.put("shipClass", "SCOUT");
        config.putAll(overrides);

        Long originId = jdbc.queryForObject(
                "insert into starport(code, name, description) values (?, ?, ?) returning id",
                Long.class,
                config.get("originCode"),
                config.get("originName"),
                config.get("originDescription"));

        Long destinationId = jdbc.queryForObject(
                "insert into starport(code, name, description) values (?, ?, ?) returning id",
                Long.class,
                config.get("destinationCode"),
                config.get("destinationName"),
                config.get("destinationDescription"));

        Long dockingBayId = jdbc.queryForObject(
                "insert into docking_bay(starport_id, bay_label, ship_class, status) values (?,?,?,?) returning id",
                Long.class,
                destinationId,
                config.get("destinationBayLabel"),
                config.get("destinationBayShipClass"),
                config.get("destinationBayStatus"));

        Long customerId = jdbc.queryForObject(
                "insert into customer(customer_code, name) values (?, ?) returning id",
                Long.class,
                config.get("customerCode"),
                config.get("customerName"));

        Long shipId = jdbc.queryForObject(
                "insert into ship(customer_id, ship_code, ship_class) values (?,?,?) returning id",
                Long.class,
                customerId,
                config.get("shipCode"),
                config.get("shipClass"));

        return Map.of(
                "originStarportId", originId,
                "destinationStarportId", destinationId,
                "dockingBayId", dockingBayId,
                "customerId", customerId,
                "shipId", shipId);
    }

    protected void truncateDomainTables() {
        jdbc.execute(
                "TRUNCATE TABLE route, reservation, docking_bay, starport, ship, customer RESTART IDENTITY CASCADE");
    }

    protected int reservationsCount(String code) {
        return jdbc.queryForObject(
                """
                select count(*)
                from reservation r
                join docking_bay d on r.docking_bay_id = d.id
                join starport s on d.starport_id = s.id
                where s.code = ?
                """,
                Integer.class,
                code);
    }

    protected Map<String, Object> makePayload(Map<String, Object> overrides) {
        Instant start = Instant.now().plusSeconds(3600);
        Instant end = start.plusSeconds(3600);
        Map<String, Object> base = new HashMap<>();
        base.put("customerCode", "CUST-001");
        base.put("shipCode", "SS-Enterprise-01");
        base.put("shipClass", "SCOUT");
        base.put("startAt", start.toString());
        base.put("endAt", end.toString());
        base.put("requestRoute", true);
        base.put("originPortId", "ALPHA-BASE");
        base.putAll(overrides);
        return base;
    }
}
