package com.galactic.telemetry.model;

import java.time.Instant;

public record RoutePlannedEvent(
        String routeId,
        String originPortId,
        String destinationPortId,
        String shipClass,
        double etaHours,
        double riskScore,
        Instant plannedAt) {}
