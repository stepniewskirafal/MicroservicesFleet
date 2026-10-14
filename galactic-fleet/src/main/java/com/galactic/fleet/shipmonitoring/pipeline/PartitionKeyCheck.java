package com.galactic.fleet.shipmonitoring.pipeline;

import com.galactic.fleet.shipmonitoring.model.RawTelemetry;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;

/**
 * Counts sensor readings whose Kafka key is not the ship id (ADR-0048).
 *
 * <p>{@code AggregationFilter} keeps its per-(ship, sensor) windows in the memory of one instance.
 * With several instances that is only correct when every reading of a ship reaches the same
 * instance — which Kafka guarantees exactly when the producer keys {@code telemetry.raw} by ship id.
 * The producer is outside this codebase, so the contract cannot be enforced here; this counter makes
 * a producer that breaks it visible. The reading is still processed.
 */
class PartitionKeyCheck {

    static final String METRIC = "telemetry.messages.key.mismatch";

    private final Counter mismatches;

    PartitionKeyCheck(MeterRegistry meterRegistry) {
        this.mismatches = Counter.builder(METRIC)
                .description("Sensor readings whose Kafka key is missing or differs from the payload's shipId")
                .register(meterRegistry);
    }

    void check(Object receivedKey, RawTelemetry reading) {
        if (reading == null || reading.shipId() == null) {
            return; // not a judgement on the key — ValidationFilter rejects such a reading
        }
        if (!reading.shipId().equals(asText(receivedKey))) {
            mismatches.increment();
        }
    }

    private static String asText(Object key) {
        if (key instanceof byte[] bytes) {
            return new String(bytes, StandardCharsets.UTF_8);
        }
        return key == null ? null : key.toString();
    }
}
