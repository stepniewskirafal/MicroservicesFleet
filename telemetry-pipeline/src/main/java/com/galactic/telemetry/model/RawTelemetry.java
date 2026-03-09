package com.galactic.telemetry.model;

import java.time.Instant;
import java.util.Map;

public record RawTelemetry(
        String shipId,
        String sensorType,
        double value,
        Instant timestamp,
        Map<String, String> metadata) {}
