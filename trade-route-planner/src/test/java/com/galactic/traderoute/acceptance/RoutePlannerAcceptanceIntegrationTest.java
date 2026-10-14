package com.galactic.traderoute.acceptance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.Nullable;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.function.Predicate;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * FROZEN black-box acceptance suite for trade-route-planner (ADR-0041).
 *
 * <p>Pins the externally observable behaviour of the service — HTTP API, Kafka contract on
 * {@code starport.route-planned} (payload fields, message key), and the Prometheus metric names the
 * infra rules scrape — WITHOUT referencing any internal package, port or adapter. The only
 * production types allowed here are none: everything goes over the wire (HTTP + Kafka + actuator).
 *
 * <p>DO NOT modify or disable these tests during the hexagonal → Spring Modulith migration. They
 * were recorded green against the hexagonal baseline and must stay green on every migration step.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Testcontainers
@Execution(ExecutionMode.SAME_THREAD)
// @SpringBootTest disables metric exporters by default — without this the /actuator/prometheus
// endpoint (part of the frozen external contract) does not exist in the test context.
@AutoConfigureObservability
class RoutePlannerAcceptanceIntegrationTest {

    private static final String PLAN_URL = "/api/v1/routes/plan";
    private static final String TOPIC = "starport.route-planned";
    private static final String ROUTE_ID_PATTERN = "ROUTE-[A-F0-9]{8}";
    private static final String UUID_PATTERN = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";

    @Container
    static final KafkaContainer KAFKA = new KafkaContainer(DockerImageName.parse("apache/kafka:3.8.1"));

    @DynamicPropertySource
    static void kafkaProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.cloud.stream.kafka.binder.brokers", KAFKA::getBootstrapServers);
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    TestRestTemplate rest;

    // ── REST: happy path ───────────────────────────────────────────────────────

    @Test
    void plan_returns_200_with_full_response_contract() throws Exception {
        ResponseEntity<String> response = postPlan(body("SP-77-NARSHADDA", "SP-02-TATOOINE", "FREIGHTER_MK2", 24.0));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getContentType()).isNotNull();
        assertThat(response.getHeaders().getContentType().isCompatibleWith(MediaType.APPLICATION_JSON))
                .isTrue();

        JsonNode bodyNode = JSON.readTree(response.getBody());
        assertThat(bodyNode.get("routeId").asText()).matches(ROUTE_ID_PATTERN);
        assertThat(bodyNode.get("etaHours").asDouble()).isBetween(18.0, 28.0);
        assertThat(bodyNode.get("riskScore").asDouble()).isBetween(0.0, 1.0);
        assertThat(bodyNode.get("correlationId").asText()).matches(UUID_PATTERN);
    }

    @ParameterizedTest(name = "shipClass={0} → 200, etaHours in [{1}, {2})")
    @CsvSource({
        "SCOUT,        8.0, 18.0",
        "FREIGHTER,   18.0, 28.0",
        "FREIGHTER_MK2,18.0, 28.0",
        "CRUISER,     12.0, 22.0",
        "DESTROYER,   20.0, 30.0",
    })
    void plan_eta_stays_in_the_per_ship_class_band(String shipClass, double min, double max) throws Exception {
        ResponseEntity<String> response = postPlan(body("SP-ETA-A", "SP-ETA-B", shipClass, 20.0));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        double eta = JSON.readTree(response.getBody()).get("etaHours").asDouble();
        assertThat(eta).isGreaterThanOrEqualTo(min).isLessThan(max);
    }

    @Test
    void risk_score_is_deterministic_and_symmetric_per_corridor() throws Exception {
        double there = riskScoreOf(postPlan(body("SP-RISK-A", "SP-RISK-B", "SCOUT", 10.0)));
        double thereAgain = riskScoreOf(postPlan(body("SP-RISK-A", "SP-RISK-B", "SCOUT", 10.0)));
        double back = riskScoreOf(postPlan(body("SP-RISK-B", "SP-RISK-A", "SCOUT", 10.0)));

        assertThat(thereAgain).isEqualTo(there);
        assertThat(back).isEqualTo(there);
    }

    @Test
    void each_plan_gets_a_fresh_correlation_id() throws Exception {
        String first = JSON.readTree(
                        postPlan(body("SP-C1", "SP-C2", "SCOUT", 10.0)).getBody())
                .get("correlationId")
                .asText();
        String second = JSON.readTree(
                        postPlan(body("SP-C1", "SP-C2", "SCOUT", 10.0)).getBody())
                .get("correlationId")
                .asText();

        assertThat(first).isNotEqualTo(second);
    }

    // ── Kafka: RoutePlanned wire contract ──────────────────────────────────────

    @Test
    void planned_route_lands_on_kafka_with_frozen_payload_and_routeId_key() throws Exception {
        String origin =
                "SP-KAFKA-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();

        try (KafkaConsumer<String, String> consumer = newConsumer()) {
            consumer.subscribe(List.of(TOPIC));

            ResponseEntity<String> response = postPlan(body(origin, "SP-02-TATOOINE", "FREIGHTER_MK2", 24.0));
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            JsonNode http = JSON.readTree(response.getBody());

            ConsumerRecord<String, String> record =
                    pollUntil(consumer, Duration.ofSeconds(30), r -> r.value().contains(origin));

            assertThat(record).as("RoutePlanned event for origin %s", origin).isNotNull();
            assertThat(record.key()).isEqualTo(http.get("routeId").asText());

            JsonNode event = JSON.readTree(record.value());
            assertThat(event.get("routeId").asText())
                    .isEqualTo(http.get("routeId").asText());
            assertThat(event.get("originPortId").asText()).isEqualTo(origin);
            assertThat(event.get("destinationPortId").asText()).isEqualTo("SP-02-TATOOINE");
            assertThat(event.get("shipClass").asText()).isEqualTo("FREIGHTER_MK2");
            assertThat(event.get("etaHours").asDouble())
                    .isEqualTo(http.get("etaHours").asDouble());
            assertThat(event.get("riskScore").asDouble())
                    .isEqualTo(http.get("riskScore").asDouble());
            // plannedAt is an ISO-8601 instant (write-dates-as-timestamps: false)
            assertThatCode(() -> Instant.parse(event.get("plannedAt").asText())).doesNotThrowAnyException();
        }
    }

    @Test
    void rejected_route_publishes_nothing_to_kafka() throws Exception {
        String origin =
                "SP-NOKAFKA-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();

        try (KafkaConsumer<String, String> consumer = newConsumer()) {
            consumer.subscribe(List.of(TOPIC));

            ResponseEntity<String> response = postPlan(body(origin, "SP-02-TATOOINE", "SCOUT", 0.5));
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);

            ConsumerRecord<String, String> record =
                    pollUntil(consumer, Duration.ofSeconds(5), r -> r.value().contains(origin));

            assertThat(record)
                    .as("no RoutePlanned event may exist for a rejected route")
                    .isNull();
        }
    }

    // ── REST: rejection vs validation (ADR-0040: consumers tell them apart) ───

    @Test
    void business_rejection_returns_422_with_ROUTE_REJECTED_error() throws Exception {
        ResponseEntity<String> response = postPlan(body("SP-A", "SP-B", "SCOUT", 0.5));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        JsonNode bodyNode = JSON.readTree(response.getBody());
        assertThat(bodyNode.get("error").asText()).isEqualTo("ROUTE_REJECTED");
        assertThat(bodyNode.get("reason").asText()).isEqualTo("INSUFFICIENT_RANGE");
        assertThat(bodyNode.get("details").asText()).isNotBlank();
    }

    @Test
    void minimum_fuel_range_of_exactly_1_LY_is_accepted() {
        assertThat(postPlan(body("SP-A", "SP-B", "SCOUT", 1.0)).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void validation_failure_returns_422_field_map_without_ROUTE_REJECTED_marker() throws Exception {
        String missingOrigin =
                """
                {
                  "destinationPortId": "SP-DEST",
                  "shipProfile": { "class": "FREIGHTER", "fuelRangeLY": 25.0 }
                }
                """;

        ResponseEntity<String> response = postPlan(missingOrigin);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        JsonNode bodyNode = JSON.readTree(response.getBody());
        assertThat(bodyNode.get("originPortId").asText()).isNotBlank();
        // starport-registry distinguishes a domain rejection from a contract violation purely by
        // this marker (ADR-0040) — a validation 422 must NOT carry error=ROUTE_REJECTED.
        assertThat(bodyNode.path("error").asText("")).isNotEqualTo("ROUTE_REJECTED");
    }

    @Test
    void null_ship_profile_returns_422_field_map() throws Exception {
        String missingProfile =
                """
                { "originPortId": "SP-ORIGIN", "destinationPortId": "SP-DEST" }
                """;

        ResponseEntity<String> response = postPlan(missingProfile);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(JSON.readTree(response.getBody()).get("shipProfile").asText())
                .isNotBlank();
    }

    @Test
    void malformed_json_returns_400() throws Exception {
        ResponseEntity<String> response = postPlan("{ bad json }");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(JSON.readTree(response.getBody()).get("error").asText()).isEqualTo("Malformed JSON");
    }

    // ── Actuator: health + frozen Prometheus metric names ──────────────────────

    @Test
    void actuator_health_reports_UP() throws Exception {
        ResponseEntity<String> response = rest.getForEntity("/actuator/health", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(JSON.readTree(response.getBody()).get("status").asText()).isEqualTo("UP");
    }

    @Test
    void prometheus_exposes_the_metric_names_the_infra_rules_scrape() {
        // Generate at least one success and one rejection so every meter exists.
        postPlan(body("SP-MET-A", "SP-MET-B", "CRUISER", 30.0));
        postPlan(body("SP-MET-A", "SP-MET-B", "SCOUT", 0.5));

        ResponseEntity<String> response = rest.getForEntity("/actuator/prometheus", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        String scrape = response.getBody();

        // Names used by infra/docker/prometheus/recording.yml and the Grafana dashboards —
        // renaming any of these breaks the fleet's dashboards and recording rules.
        assertThat(scrape).contains("routes_planned_count_total");
        assertThat(scrape).contains("routes_rejected_count_total");
        assertThat(scrape).contains("reason=\"INSUFFICIENT_RANGE\"");
        assertThat(scrape).contains("routes_risk_score_count");
        assertThat(scrape).contains("routes_risk_score_sum");
        assertThat(scrape).contains("routes_plan_seconds_bucket");
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private ResponseEntity<String> postPlan(String jsonBody) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.postForEntity(PLAN_URL, new HttpEntity<>(jsonBody, headers), String.class);
    }

    private static String body(String origin, String dest, String shipClass, double fuel) {
        return """
                {
                  "originPortId": "%s",
                  "destinationPortId": "%s",
                  "shipProfile": { "class": "%s", "fuelRangeLY": %s }
                }
                """
                .formatted(origin, dest, shipClass, fuel);
    }

    private static double riskScoreOf(ResponseEntity<String> response) throws Exception {
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return JSON.readTree(response.getBody()).get("riskScore").asDouble();
    }

    private static KafkaConsumer<String, String> newConsumer() {
        Properties props = new Properties();
        props.putAll(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "acceptance-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName(),
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName()));
        return new KafkaConsumer<>(props);
    }

    @Nullable
    private static ConsumerRecord<String, String> pollUntil(
            KafkaConsumer<String, String> consumer, Duration timeout, Predicate<ConsumerRecord<String, String>> match) {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(500))) {
                if (match.test(record)) {
                    return record;
                }
            }
        }
        return null;
    }
}
