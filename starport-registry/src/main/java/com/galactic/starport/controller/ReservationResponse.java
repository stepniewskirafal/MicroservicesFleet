package com.galactic.starport.controller;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.Builder;

@Builder
public record ReservationResponse(
        Long reservationId,
        String starportCode,
        Long bayNumber,
        Instant startAt,
        Instant endAt,
        BigDecimal feeCharged,
        Route route) {

    @Builder
    record Route(UUID routeId, Double etaLY, Double riskScore) {}
}
