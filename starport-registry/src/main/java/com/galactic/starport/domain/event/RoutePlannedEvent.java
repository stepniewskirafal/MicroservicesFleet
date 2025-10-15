package com.galactic.starport.domain.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Zdarzenie domenowe informujące, że trasa została zaplanowana.
 * Wartości `etaHours` i `riskScore` odpowiadają danym zwróconym przez usługę B.
 * `reservationId` służy do powiązania tego wyniku z rezerwacją w A.
 */
public record RoutePlannedEvent(
        String eventId,
        Instant occurredAt,
        UUID reservationId,
        String originPortCode,
        String destinationPortCode,
        String routeId,
        double etaHours,
        double riskScore) {}
