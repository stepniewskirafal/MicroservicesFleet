package com.galactic.telemetry.model;

import java.time.Instant;

public record AnomalyAlert(
        String shipId,
        SensorType sensorType,
        Severity severity,
        String description,
        double currentValue,
        double threshold,
        double rollingAvg,
        String shipClass,
        String currentSector,
        Instant detectedAt) {

    public enum Severity {
        WARNING,
        CRITICAL
    }
}
