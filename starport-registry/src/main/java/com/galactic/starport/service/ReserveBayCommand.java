package com.galactic.starport.service;

import java.time.Instant;
import lombok.Builder;

@Builder
public record ReserveBayCommand(
        String starportCode,
        Long customerId,
        String shipId,
        ShipClass shipClass,
        Instant startAt,
        Instant endAt,
        boolean requestRoute,
        String originPortId) {

    public enum ShipClass {
        SCOUT,
        FREIGHTER,
        CRUISER,
        UNKNOWN
    }
}
