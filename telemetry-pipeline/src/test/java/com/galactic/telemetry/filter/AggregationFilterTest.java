package com.galactic.telemetry.filter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.galactic.telemetry.model.AggregatedTelemetry;
import com.galactic.telemetry.model.EnrichedTelemetry;
import com.galactic.telemetry.model.SensorType;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AggregationFilterTest {

    private AggregationFilter filter;

    @BeforeEach
    void setUp() {
        filter = new AggregationFilter();
        filter.clearWindows();
    }

    @Test
    void firstMessage_initializesWindow() {
        EnrichedTelemetry input = enriched("SHIP-001", SensorType.TEMPERATURE, 42.0);

        AggregatedTelemetry result = filter.apply(input);

        assertThat(result.currentValue()).isEqualTo(42.0);
        assertThat(result.rollingAvg()).isEqualTo(42.0);
        assertThat(result.rollingMax()).isEqualTo(42.0);
        assertThat(result.rollingStdDev()).isEqualTo(0.0);
        assertThat(result.windowSampleCount()).isEqualTo(1);
    }

    @Test
    void multipleMessages_computeRollingStats() {
        filter.apply(enriched("SHIP-001", SensorType.TEMPERATURE, 40.0));
        filter.apply(enriched("SHIP-001", SensorType.TEMPERATURE, 44.0));
        AggregatedTelemetry result = filter.apply(enriched("SHIP-001", SensorType.TEMPERATURE, 46.0));

        assertThat(result.windowSampleCount()).isEqualTo(3);
        assertThat(result.rollingAvg()).isCloseTo(43.33, within(0.01));
        assertThat(result.rollingMax()).isEqualTo(46.0);
        assertThat(result.rollingStdDev()).isGreaterThan(0.0);
    }

    @Test
    void differentShips_haveIndependentWindows() {
        filter.apply(enriched("SHIP-001", SensorType.TEMPERATURE, 100.0));
        filter.apply(enriched("SHIP-002", SensorType.TEMPERATURE, 200.0));

        AggregatedTelemetry result1 = filter.apply(enriched("SHIP-001", SensorType.TEMPERATURE, 110.0));
        AggregatedTelemetry result2 = filter.apply(enriched("SHIP-002", SensorType.TEMPERATURE, 210.0));

        assertThat(result1.rollingAvg()).isCloseTo(105.0, within(0.01));
        assertThat(result2.rollingAvg()).isCloseTo(205.0, within(0.01));
    }

    @Test
    void differentSensors_haveIndependentWindows() {
        filter.apply(enriched("SHIP-001", SensorType.TEMPERATURE, 42.0));
        filter.apply(enriched("SHIP-001", SensorType.RADIATION, 500.0));

        AggregatedTelemetry tempResult = filter.apply(enriched("SHIP-001", SensorType.TEMPERATURE, 44.0));
        AggregatedTelemetry radResult = filter.apply(enriched("SHIP-001", SensorType.RADIATION, 600.0));

        assertThat(tempResult.rollingAvg()).isCloseTo(43.0, within(0.01));
        assertThat(radResult.rollingAvg()).isCloseTo(550.0, within(0.01));
    }

    @Test
    void preservesEnrichedFields() {
        EnrichedTelemetry input = new EnrichedTelemetry(
                "SHIP-003", "Cruiser", "Proxima-B",
                SensorType.HULL_INTEGRITY, 95.0, Instant.now(),
                20.0, 100.0, Map.of());

        AggregatedTelemetry result = filter.apply(input);

        assertThat(result.shipClass()).isEqualTo("Cruiser");
        assertThat(result.currentSector()).isEqualTo("Proxima-B");
        assertThat(result.lowerThreshold()).isEqualTo(20.0);
        assertThat(result.upperThreshold()).isEqualTo(100.0);
    }

    private EnrichedTelemetry enriched(String shipId, SensorType sensorType, double value) {
        return new EnrichedTelemetry(
                shipId, "TestClass", "TestSector",
                sensorType, value, Instant.now(),
                0.0, 1000.0, Map.of());
    }
}
