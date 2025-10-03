package com.galactic.starport.application.event;

import com.galactic.starport.domain.enums.ShipClass;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record StarportReservationCreated(
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
