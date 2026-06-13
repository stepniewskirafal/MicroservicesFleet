package com.galactic.telemetry.filter;

import static org.assertj.core.api.Assertions.assertThat;

import com.galactic.telemetry.model.EnrichedTelemetry;
import com.galactic.telemetry.model.SensorType;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

@Execution(ExecutionMode.CONCURRENT)
class AggregationFilterMemoryCapTest {

    private static final int MAX_WINDOWS = 10_000;
    private static final int EVICTION_INTERVAL = 1_000;

    @Test
    void hard_caps_window_count_under_a_flood_of_distinct_fresh_keys() {
        AggregationFilter filter = new AggregationFilter();
        // All windows share one fresh timestamp, so none ever expire — only the LRU hard cap can
        // bound the map. Before the fix, expiry-only eviction left the map growing unbounded here.
        Instant fresh = Instant.parse("2026-01-01T00:00:00Z");

        int flood = MAX_WINDOWS + 5 * EVICTION_INTERVAL; // 15_000 distinct (ship,sensor) keys
        for (int i = 0; i < flood; i++) {
            filter.apply(new EnrichedTelemetry(
                    "SHIP-" + i, "Corvette", "sector-7", SensorType.TEMPERATURE, 42.0, fresh, 0.0, 100.0, Map.of()));
        }

        assertThat(filter.activeWindowCount())
                .as("window map must stay hard-capped, not grow with every distinct fresh key")
                .isLessThanOrEqualTo(MAX_WINDOWS + EVICTION_INTERVAL)
                .isLessThan(flood);
    }
}
