package com.galactic.traderoute.adapter.out.kafka;

import com.galactic.traderoute.domain.model.RoutePlannedEvent;
import java.time.Instant;

/**
 * Wire contract for the {@code routePlanned-out-0} Kafka binding. Decouples the published message
 * shape from the domain {@link RoutePlannedEvent}: a domain refactor can no longer silently change
 * what telemetry consumes. Field names and order mirror the historical payload — keep them stable.
 */
record RoutePlannedMessage(
        String routeId,
        String originPortId,
        String destinationPortId,
        String shipClass,
        double etaHours,
        double riskScore,
        Instant plannedAt) {

    static RoutePlannedMessage from(RoutePlannedEvent event) {
        return new RoutePlannedMessage(
                event.routeId(),
                event.originPortId(),
                event.destinationPortId(),
                event.shipClass(),
                event.etaHours(),
                event.riskScore(),
                event.plannedAt());
    }
}
