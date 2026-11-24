package com.galactic.fleet.activityfeed.model;

import java.math.BigDecimal;
import java.time.Instant;

public record EnrichedReservationEvent(
        String eventType,
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
        long durationHours,
        Instant processedAt,
        String processedBy) {}
