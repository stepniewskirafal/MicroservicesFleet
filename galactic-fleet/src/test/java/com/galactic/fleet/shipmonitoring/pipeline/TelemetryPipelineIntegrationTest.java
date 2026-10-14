package com.galactic.fleet.shipmonitoring.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import com.galactic.fleet.shipmonitoring.model.RawTelemetry;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.stream.binder.test.InputDestination;
import org.springframework.cloud.stream.binder.test.OutputDestination;
import org.springframework.cloud.stream.binder.test.TestChannelBinderConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.GenericMessage;
import org.springframework.modulith.test.ApplicationModuleTest;
import org.springframework.test.context.TestPropertySource;

// Module test (Spring Modulith): boots ONLY the shipmonitoring module — its own beans plus infrastructure
// auto-configuration. No reservations beans are loaded, so no database is needed: the data-layer
// auto-configuration is switched off and the test stays in the Docker-free unit phase. The Event
// Publication Registry (ADR-0046) stores publications through JPA, so it is switched off with it —
// this test exercises the sensor pipeline, which publishes and listens to no application events.
@ApplicationModuleTest
@TestPropertySource(
        properties = "spring.autoconfigure.exclude="
                + "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,"
                + "org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration,"
                + "org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration,"
                + "org.springframework.modulith.events.jpa.JpaEventPublicationAutoConfiguration,"
                + "org.springframework.modulith.events.jpa.archiving.ArchivingAutoConfiguration,"
                + "org.springframework.modulith.events.config.EventPublicationAutoConfiguration")
@Import(TestChannelBinderConfiguration.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class TelemetryPipelineIntegrationTest {

    @Autowired
    private InputDestination input;

    @Autowired
    private OutputDestination output;

    @BeforeEach
    void drainOutput() {
        // Drain any leftover messages from previous tests
        while (output.receive(100) != null) {
            // discard
        }
    }

    @Test
    @Order(1)
    void telemetryExceedingThreshold_producesAlert() {
        RawTelemetry raw = new RawTelemetry("SHIP-001", "TEMPERATURE", 500.0, Instant.now(), Map.of());

        input.send(new GenericMessage<>(raw));

        Message<byte[]> result = output.receive(5000);
        assertThat(result).isNotNull();

        String payload = new String(result.getPayload());
        assertThat(payload).contains("SHIP-001");
        assertThat(payload).contains("CRITICAL");
    }

    @Test
    @Order(2)
    void fuelBelowThreshold_producesCriticalAlert() {
        RawTelemetry raw = new RawTelemetry("SHIP-002", "FUEL_LEVEL", 2.0, Instant.now(), Map.of());

        input.send(new GenericMessage<>(raw));

        Message<byte[]> result = output.receive(5000);
        assertThat(result).isNotNull();

        String payload = new String(result.getPayload());
        assertThat(payload).contains("CRITICAL");
        assertThat(payload).contains("FUEL_LEVEL");
    }

    @Test
    @Order(3)
    void normalTelemetry_noAlertProduced() {
        RawTelemetry raw = new RawTelemetry("SHIP-001", "TEMPERATURE", 42.5, Instant.now(), Map.of());

        input.send(new GenericMessage<>(raw));

        Message<byte[]> result = output.receive(1000);
        assertThat(result).isNull();
    }

    @Test
    @Order(4)
    void invalidTelemetry_noAlertProduced() {
        RawTelemetry raw = new RawTelemetry(null, "TEMPERATURE", 42.5, Instant.now(), Map.of());

        input.send(new GenericMessage<>(raw));

        Message<byte[]> result = output.receive(1000);
        assertThat(result).isNull();
    }

    @Test
    @Order(5)
    void unknownSensorType_droppedByValidation() {
        RawTelemetry raw = new RawTelemetry("SHIP-001", "UNKNOWN_SENSOR", 42.5, Instant.now(), Map.of());

        input.send(new GenericMessage<>(raw));

        Message<byte[]> result = output.receive(1000);
        assertThat(result).isNull();
    }
}
