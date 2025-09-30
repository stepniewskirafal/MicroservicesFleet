package com.galactic.starport.domain.event;

import java.time.Instant;

public record ReservationCreated(
        String eventId,
        Instant occurredAt,
        String starportCode,
        String reservationId,
        String bayNumber,
        String shipId,
        String shipClass,
        Instant startAt,
        Instant endAt,
        String feeCharged) {}
