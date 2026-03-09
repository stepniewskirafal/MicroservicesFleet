package com.galactic.telemetry.filter;

import static org.assertj.core.api.Assertions.assertThat;

import com.galactic.telemetry.config.SensorThresholdProperties;
import com.galactic.telemetry.model.EnrichedTelemetry;
import com.galactic.telemetry.model.SensorType;
import com.galactic.telemetry.model.ValidatedTelemetry;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class EnrichmentFilterTest {

    private EnrichmentFilter filter;

    @BeforeEach
    void setUp() {
        filter = new EnrichmentFilter(new SensorThresholdProperties());
    }

    @Test
    void knownShip_enrichedWithClassAndSector() {
        ValidatedTelemetry input = new ValidatedTelemetry(
                "SHIP-001", SensorType.TEMPERATURE, 42.5, Instant.now(), Map.of());

        EnrichedTelemetry result = filter.apply(input);

        assertThat(result.shipId()).isEqualTo("SHIP-001");
        assertThat(result.shipClass()).isEqualTo("Corvette");
        assertThat(result.currentSector()).isEqualTo("Alpha-Centauri");
        assertThat(result.sensorType()).isEqualTo(SensorType.TEMPERATURE);
        assertThat(result.value()).isEqualTo(42.5);
    }

    @Test
    void unknownShip_defaultShipInfo() {
        ValidatedTelemetry input = new ValidatedTelemetry(
                "SHIP-999", SensorType.RADIATION, 100.0, Instant.now(), Map.of());

        EnrichedTelemetry result = filter.apply(input);

        assertThat(result.shipClass()).isEqualTo("Unknown");
        assertThat(result.currentSector()).isEqualTo("Unknown");
    }

    @Test
    void temperatureSensor_hasCorrectThresholds() {
        ValidatedTelemetry input = new ValidatedTelemetry(
                "SHIP-001", SensorType.TEMPERATURE, 42.5, Instant.now(), Map.of());

        EnrichedTelemetry result = filter.apply(input);

        assertThat(result.lowerThreshold()).isEqualTo(-50.0);
        assertThat(result.upperThreshold()).isEqualTo(300.0);
    }

    @Test
    void fuelLevelSensor_hasCorrectThresholds() {
        ValidatedTelemetry input = new ValidatedTelemetry(
                "SHIP-002", SensorType.FUEL_LEVEL, 75.0, Instant.now(), Map.of());

        EnrichedTelemetry result = filter.apply(input);

        assertThat(result.lowerThreshold()).isEqualTo(5.0);
        assertThat(result.upperThreshold()).isEqualTo(100.0);
    }

    @Test
    void metadata_isPreserved() {
        Map<String, String> meta = Map.of("deck", "3", "zone", "aft");
        ValidatedTelemetry input = new ValidatedTelemetry(
                "SHIP-003", SensorType.HULL_INTEGRITY, 95.0, Instant.now(), meta);

        EnrichedTelemetry result = filter.apply(input);

        assertThat(result.metadata()).isEqualTo(meta);
    }
}
