package com.galactic.telemetry.config;

import com.galactic.telemetry.model.SensorType;
import java.util.EnumMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "telemetry.thresholds")
public class SensorThresholdProperties {

    private final Map<SensorType, ThresholdRange> sensors;

    public SensorThresholdProperties() {
        // Defaults — overridable via application.yml
        this.sensors = new EnumMap<>(SensorType.class);
        sensors.put(SensorType.TEMPERATURE, new ThresholdRange(-50.0, 300.0));
        sensors.put(SensorType.RADIATION, new ThresholdRange(0.0, 1000.0));
        sensors.put(SensorType.FUEL_LEVEL, new ThresholdRange(5.0, 100.0));
        sensors.put(SensorType.ENGINE_VIBRATION, new ThresholdRange(0.0, 80.0));
        sensors.put(SensorType.HULL_INTEGRITY, new ThresholdRange(20.0, 100.0));
        sensors.put(SensorType.OXYGEN_LEVEL, new ThresholdRange(18.0, 100.0));
    }

    public Map<SensorType, ThresholdRange> getSensors() {
        return sensors;
    }

    public void setSensors(Map<SensorType, ThresholdRange> overrides) {
        if (overrides != null) {
            this.sensors.putAll(overrides);
        }
    }

    public ThresholdRange rangeFor(SensorType sensorType) {
        return sensors.getOrDefault(sensorType, new ThresholdRange(Double.MIN_VALUE, Double.MAX_VALUE));
    }

    public record ThresholdRange(double lower, double upper) {}
}
