package com.galactic.telemetry.model;

import java.time.Instant;

public record AggregatedTelemetry(
        String shipId,
        String shipClass,
        String currentSector,
        SensorType sensorType,
        double currentValue,
        double rollingAvg,
        double rollingMax,
        double rollingStdDev,
        long windowSampleCount,
        Instant windowStart,
        Instant windowEnd,
        double lowerThreshold,
        double upperThreshold) {}
