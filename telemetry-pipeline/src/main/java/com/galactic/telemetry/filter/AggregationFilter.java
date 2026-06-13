package com.galactic.telemetry.filter;

import com.galactic.telemetry.model.AggregatedTelemetry;
import com.galactic.telemetry.model.EnrichedTelemetry;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Computes running statistics per (shipId, sensorType) pair — this is the one <b>stateful</b>
 * filter in the pipeline.
 *
 * <p>Implementation: each key holds a {@link WindowState} accumulating a <em>cumulative</em> mean,
 * max and variance via Welford's online algorithm (not an exponential moving average — older
 * samples are weighted equally with newer ones). The window is <b>tumbling, not sliding</b>: it is
 * never trimmed sample-by-sample; instead the whole window resets once {@code DEFAULT_WINDOW} (5
 * min) elapses between {@code windowStart} and the incoming timestamp. The {@link ConcurrentHashMap}
 * holds only derived statistics (not raw samples). Memory is <b>hard-capped</b> at {@code
 * MAX_WINDOWS}: eviction first drops expired windows and, if a burst of distinct <em>fresh</em> keys
 * still exceeds the cap, evicts the least-recently-updated windows (LRU) — so a flood of new
 * (ship,sensor) pairs cannot grow the map without bound.
 *
 * <p>For true sliding-window or EMA semantics with fault tolerance (changelog-backed), this could be
 * replaced by a Kafka Streams KStream/KTable processor. The current approach keeps the filter simple
 * and unit-testable for moderate throughput.
 */
public class AggregationFilter implements Function<EnrichedTelemetry, AggregatedTelemetry> {

    private static final Logger log = LoggerFactory.getLogger(AggregationFilter.class);
    private static final Duration DEFAULT_WINDOW = Duration.ofMinutes(5);
    private static final int MAX_WINDOWS = 10_000;
    private static final int EVICTION_INTERVAL = 1000;

    private final ConcurrentMap<String, WindowState> windows = new ConcurrentHashMap<>();
    private final AtomicLong operationCount = new AtomicLong();

    @Override
    public AggregatedTelemetry apply(EnrichedTelemetry enriched) {
        if (operationCount.incrementAndGet() % EVICTION_INTERVAL == 0) {
            evictWindows(enriched.timestamp());
        }
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

    private void evictWindows(Instant now) {
        if (windows.size() <= MAX_WINDOWS) {
            return;
        }
        // Cheap first pass: drop windows whose tumbling interval has already elapsed.
        windows.entrySet().removeIf(entry -> isExpired(entry.getValue(), now));

        // Hard cap: expiry alone does NOT bound memory when a burst of >MAX_WINDOWS distinct, still
        // fresh (ship,sensor) keys arrives. Evict the least-recently-updated windows (smallest
        // windowEnd) until back at capacity, so the map can never grow without bound.
        int overflow = windows.size() - MAX_WINDOWS;
        if (overflow > 0) {
            windows.entrySet().stream()
                    .sorted(Comparator.comparing(entry -> entry.getValue().windowEnd))
                    .limit(overflow)
                    .map(Map.Entry::getKey)
                    .toList()
                    .forEach(windows::remove);
            log.warn("AggregationFilter hard cap reached — evicted {} least-recently-updated window(s)", overflow);
        }
    }

    // Visible for testing
    void clearWindows() {
        windows.clear();
    }

    // Visible for testing
    int activeWindowCount() {
        return windows.size();
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
                double avg,
                double max,
                double currentValue,
                double stdDev,
                long count,
                Instant windowStart,
                Instant windowEnd,
                double m2) {
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
