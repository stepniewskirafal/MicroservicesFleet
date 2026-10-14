package com.galactic.traderoute.planning;

import java.time.Instant;
import lombok.Builder;

@Builder
public record RoutePlannedEvent(
        String routeId,
        String originPortId,
        String destinationPortId,
        String shipClass,
        double etaHours,
        double riskScore,
        Instant plannedAt) {}
