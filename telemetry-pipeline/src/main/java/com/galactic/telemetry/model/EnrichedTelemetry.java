package com.galactic.telemetry.model;

import java.time.Instant;
import java.util.Map;

public record EnrichedTelemetry(
        String shipId,
        String shipClass,
        String currentSector,
        SensorType sensorType,
        double value,
        Instant timestamp,
        double lowerThreshold,
        double upperThreshold,
        Map<String, String> metadata) {}
