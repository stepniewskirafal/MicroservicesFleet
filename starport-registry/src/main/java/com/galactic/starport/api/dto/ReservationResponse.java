package com.galactic.starport.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record ReservationResponse(
        UUID reservationId,
        String starportCode,
        UUID bayNumber,
        Instant startAt,
        Instant endAt,
        BigDecimal feeCharged,
        String routeId) {}
