package com.galactic.starport.controller;

import java.math.BigDecimal;
import java.time.Instant;
import lombok.Builder;

@Builder
public record ReservationResponse(
        Long reservationId,
        String starportCode,
        String bayNumber,
        Instant startAt,
        Instant endAt,
        BigDecimal feeCharged,
        Route route) {

    @Builder
    record Route(
            String routeCode,
            String startStarportCode,
            String destinationStarportCode,
            Double etaLightYears,
            Double riskScore) {}
}
