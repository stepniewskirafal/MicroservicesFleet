package com.galactic.telemetry.filter;

import com.galactic.telemetry.model.AggregatedTelemetry;
import com.galactic.telemetry.model.EnrichedTelemetry;
import com.galactic.telemetry.model.SensorType;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Computes rolling-window statistics per (shipId, sensorType) pair.
 *
 * <p>Implementation note: this filter maintains an in-memory sliding window using exponential
 * moving averages. The ConcurrentHashMap holds only derived statistics (not raw data), keeping
 * memory bounded. The map is keyed by (shipId + sensorType) and entries expire when the window
 * duration elapses without new data.
 *
 * <p>For Kafka Streams-based stateful aggregation (with fault tolerance via changelogs), this could
 * be replaced with a KStream/KTable processor. The current approach keeps the filter simple and
 * testable for moderate throughput.
 */
public class AggregationFilter implements Function<EnrichedTelemetry, AggregatedTelemetry> {

    private static final Logger log = LoggerFactory.getLogger(AggregationFilter.class);
    private static final Duration DEFAULT_WINDOW = Duration.ofMinutes(5);

    private final ConcurrentMap<String, WindowState> windows = new ConcurrentHashMap<>();

    @Override
    public AggregatedTelemetry apply(EnrichedTelemetry enriched) {
        String key = enriched.shipId() + ":" + enriched.sensorType().name();
        Instant now = enriched.timestamp();

        WindowState state = windows.compute(key, (k, existing) -> {
            if (existing == null || isExpired(existing, now)) {
                return new WindowState(enriched.value(), enriched.value(), enriched.value(), 0.0, 1, now, now);
            }
            return existing.update(enriched.value(), now);
        });

        log.debug(
                "Aggregated ship={} sensor={}: avg={}, max={}, stdDev={}, samples={}",
                enriched.shipId(),
                enriched.sensorType(),
                state.avg,
                state.max,
                state.stdDev,
                state.count);

        return new AggregatedTelemetry(
                enriched.shipId(),
                enriched.shipClass(),
                enriched.currentSector(),
                enriched.sensorType(),
                enriched.value(),
                state.avg,
                state.max,
                state.stdDev,
                state.count,
                state.windowStart,
                state.windowEnd,
                enriched.lowerThreshold(),
                enriched.upperThreshold());
    }

    private boolean isExpired(WindowState state, Instant now) {
        return Duration.between(state.windowStart, now).compareTo(DEFAULT_WINDOW) > 0;
    }

    // Visible for testing
    void clearWindows() {
        windows.clear();
    }

    static final class WindowState {
        final double avg;
        final double max;
        final double currentValue;
        final double stdDev;
        final long count;
        final Instant windowStart;
        final Instant windowEnd;

        // Running variance using Welford's online algorithm
        private final double m2;

        WindowState(
                double avg,
                double max,
                double currentValue,
                double stdDev,
                long count,
                Instant windowStart,
                Instant windowEnd) {
            this.avg = avg;
            this.max = max;
            this.currentValue = currentValue;
            this.stdDev = stdDev;
            this.count = count;
            this.windowStart = windowStart;
            this.windowEnd = windowEnd;
            this.m2 = stdDev * stdDev * count; // reconstruct m2
        }

        private WindowState(
                double avg, double max, double currentValue, double stdDev, long count,
                Instant windowStart, Instant windowEnd, double m2) {
            this.avg = avg;
            this.max = max;
            this.currentValue = currentValue;
            this.stdDev = stdDev;
            this.count = count;
            this.windowStart = windowStart;
            this.windowEnd = windowEnd;
            this.m2 = m2;
        }

        WindowState update(double newValue, Instant timestamp) {
            long newCount = count + 1;
            double delta = newValue - avg;
            double newAvg = avg + delta / newCount;
            double delta2 = newValue - newAvg;
            double newM2 = m2 + delta * delta2;
            double newStdDev = newCount > 1 ? Math.sqrt(newM2 / (newCount - 1)) : 0.0;
            double newMax = Math.max(max, newValue);

            return new WindowState(newAvg, newMax, newValue, newStdDev, newCount, windowStart, timestamp, newM2);
        }
    }
}
