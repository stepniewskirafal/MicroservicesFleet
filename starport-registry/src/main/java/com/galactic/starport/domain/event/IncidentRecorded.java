package com.galactic.starport.domain.event;

import java.time.Instant;
import java.util.UUID;

public record IncidentRecorded(
        String eventId,
        Instant occurredAt,
        String starportCode,
        String incidentType,
        String severity, // np. INFO/WARN/ERROR
        String description,
        UUID reservationId // opcjonalnie, może być null
        ) {}
