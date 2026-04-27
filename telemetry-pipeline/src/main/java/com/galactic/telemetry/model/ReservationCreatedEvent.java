package com.galactic.telemetry.model;

import java.math.BigDecimal;
import java.time.Instant;

public record ReservationCreatedEvent(
        Long reservationId,
        String status,
        String starportCode,
        String dockingBayLabel,
        String customerCode,
        String shipCode,
        String routeCode,
        Instant startAt,
        Instant endAt,
        BigDecimal feeCharged,
        // Set by producer (starport-registry/OutboxAppender) right before persisting to event_outbox.
        // Used by telemetry-pipeline to compute events.reservation.lag.seconds (e2e async latency).
        // Nullable for forward compatibility — older events in flight may not carry this field.
        Instant producedAt) {}
