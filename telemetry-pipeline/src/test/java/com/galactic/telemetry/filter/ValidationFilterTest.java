package com.galactic.telemetry.filter;

import static org.assertj.core.api.Assertions.assertThat;

import com.galactic.telemetry.model.RawTelemetry;
import com.galactic.telemetry.model.SensorType;
import com.galactic.telemetry.model.ValidatedTelemetry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ValidationFilterTest {

    private ValidationFilter filter;
    private SimpleMeterRegistry meterRegistry;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        filter = new ValidationFilter(meterRegistry);
    }

    @Test
    void validMessage_returnsValidatedTelemetry() {
        RawTelemetry raw =
                new RawTelemetry("SHIP-001", "TEMPERATURE", 42.5, Instant.now(), Map.of("location", "engine-room"));

        ValidatedTelemetry result = filter.apply(raw);

        assertThat(result).isNotNull();
        assertThat(result.shipId()).isEqualTo("SHIP-001");
        assertThat(result.sensorType()).isEqualTo(SensorType.TEMPERATURE);
        assertThat(result.value()).isEqualTo(42.5);
        assertThat(result.metadata()).containsEntry("location", "engine-room");
    }

    @Test
    void nullMessage_returnsNull() {
        assertThat(filter.apply(null)).isNull();
        assertThat(meterRegistry.counter("telemetry.messages.invalid").count()).isEqualTo(1.0);
    }

    @Test
    void missingShipId_returnsNull() {
        RawTelemetry raw = new RawTelemetry(null, "TEMPERATURE", 42.5, Instant.now(), Map.of());

        assertThat(filter.apply(raw)).isNull();
    }

    @Test
    void blankShipId_returnsNull() {
        RawTelemetry raw = new RawTelemetry("  ", "TEMPERATURE", 42.5, Instant.now(), Map.of());

        assertThat(filter.apply(raw)).isNull();
    }

    @Test
    void unknownSensorType_returnsNull() {
        RawTelemetry raw = new RawTelemetry("SHIP-001", "GRAVITY_WAVE", 42.5, Instant.now(), Map.of());

        assertThat(filter.apply(raw)).isNull();
    }

    @Test
    void nanValue_returnsNull() {
        RawTelemetry raw = new RawTelemetry("SHIP-001", "TEMPERATURE", Double.NaN, Instant.now(), Map.of());

        assertThat(filter.apply(raw)).isNull();
    }

    @Test
    void infiniteValue_returnsNull() {
        RawTelemetry raw =
                new RawTelemetry("SHIP-001", "TEMPERATURE", Double.POSITIVE_INFINITY, Instant.now(), Map.of());

        assertThat(filter.apply(raw)).isNull();
    }

    @Test
    void missingTimestamp_returnsNull() {
        RawTelemetry raw = new RawTelemetry("SHIP-001", "TEMPERATURE", 42.5, null, Map.of());

        assertThat(filter.apply(raw)).isNull();
    }

    @Test
    void nullMetadata_defaultsToEmptyMap() {
        RawTelemetry raw = new RawTelemetry("SHIP-001", "TEMPERATURE", 42.5, Instant.now(), null);

        ValidatedTelemetry result = filter.apply(raw);

        assertThat(result).isNotNull();
        assertThat(result.metadata()).isEmpty();
    }

    @Test
    void receivedCounter_incrementsForEveryMessage() {
        RawTelemetry valid = new RawTelemetry("SHIP-001", "TEMPERATURE", 42.5, Instant.now(), Map.of());
        RawTelemetry invalid = new RawTelemetry(null, "TEMPERATURE", 42.5, Instant.now(), Map.of());

        filter.apply(valid);
        filter.apply(invalid);

        assertThat(meterRegistry.counter("telemetry.messages.received").count()).isEqualTo(2.0);
    }

    @Test
    void allSensorTypes_areAccepted() {
        for (SensorType type : SensorType.values()) {
            RawTelemetry raw = new RawTelemetry("SHIP-001", type.name(), 50.0, Instant.now(), Map.of());
            assertThat(filter.apply(raw)).isNotNull();
        }
    }
}
