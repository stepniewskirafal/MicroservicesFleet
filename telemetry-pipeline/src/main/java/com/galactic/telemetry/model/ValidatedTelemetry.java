package com.galactic.telemetry.model;

import java.time.Instant;
import java.util.Map;

public record ValidatedTelemetry(
        String shipId, SensorType sensorType, double value, Instant timestamp, Map<String, String> metadata) {}
