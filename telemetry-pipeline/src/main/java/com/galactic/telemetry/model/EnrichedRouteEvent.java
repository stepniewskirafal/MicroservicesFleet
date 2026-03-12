package com.galactic.telemetry.model;

import java.time.Instant;

public record EnrichedRouteEvent(
        String eventType,
        String routeId,
        String originPortId,
        String destinationPortId,
        String shipClass,
        double etaHours,
        double riskScore,
        String riskLevel,
        Instant plannedAt,
        Instant processedAt,
        String processedBy) {}
