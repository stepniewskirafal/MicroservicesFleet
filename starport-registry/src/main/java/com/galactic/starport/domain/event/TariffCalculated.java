package com.galactic.starport.domain.event;

import com.galactic.starport.domain.enums.ShipClass;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record TariffCalculated(
        String eventId,
        Instant occurredAt,
        String starportCode,
        UUID reservationId,
        ShipClass shipClass,
        long durationHours,
        BigDecimal amount) {}
