package com.galactic.telemetry.filter;

import com.galactic.telemetry.model.RawTelemetry;
import com.galactic.telemetry.model.SensorType;
import com.galactic.telemetry.model.ValidatedTelemetry;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Map;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ValidationFilter implements Function<RawTelemetry, ValidatedTelemetry> {

    private static final Logger log = LoggerFactory.getLogger(ValidationFilter.class);

    private final Counter receivedCounter;
    private final Counter invalidCounter;

    public ValidationFilter(MeterRegistry meterRegistry) {
        this.receivedCounter = Counter.builder("telemetry.messages.received")
                .description("Total raw telemetry messages received")
                .register(meterRegistry);
        this.invalidCounter = Counter.builder("telemetry.messages.invalid")
                .description("Telemetry messages rejected by validation")
                .register(meterRegistry);
    }

    @Override
    public ValidatedTelemetry apply(RawTelemetry raw) {
        receivedCounter.increment();

        if (raw == null) {
            log.warn("Received null telemetry message — dropping");
            invalidCounter.increment();
            return null;
        }

        if (raw.shipId() == null || raw.shipId().isBlank()) {
            log.warn("Missing shipId — dropping message");
            invalidCounter.increment();
            return null;
        }

        if (!SensorType.isValid(raw.sensorType())) {
            log.warn("Unknown sensor type '{}' for ship {} — dropping", raw.sensorType(), raw.shipId());
            invalidCounter.increment();
            return null;
        }

        if (Double.isNaN(raw.value()) || Double.isInfinite(raw.value())) {
            log.warn("Invalid value {} for sensor {} on ship {} — dropping", raw.value(), raw.sensorType(), raw.shipId());
            invalidCounter.increment();
            return null;
        }

        if (raw.timestamp() == null) {
            log.warn("Missing timestamp for ship {} — dropping", raw.shipId());
            invalidCounter.increment();
            return null;
        }

        SensorType sensorType = SensorType.valueOf(raw.sensorType());

        return new ValidatedTelemetry(
                raw.shipId(),
                sensorType,
                raw.value(),
                raw.timestamp(),
                raw.metadata() != null ? raw.metadata() : Map.of());
    }
}
