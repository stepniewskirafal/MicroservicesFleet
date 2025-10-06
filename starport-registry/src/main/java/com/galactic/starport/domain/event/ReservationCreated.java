package com.galactic.starport.domain.event;

import com.galactic.starport.domain.enums.ShipClass;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record ReservationCreated(
        String eventId,
        Instant occurredAt,
        String starportCode,
        UUID reservationId,
        UUID bayNumber,
        String shipId,
        ShipClass shipClass,
        Instant startAt,
        Instant endAt,
        BigDecimal feeCharged) {}
