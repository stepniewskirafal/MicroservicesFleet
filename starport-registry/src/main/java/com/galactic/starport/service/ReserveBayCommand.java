package com.galactic.starport.service;

import java.time.Instant;
import lombok.Builder;

@Builder
public record ReserveBayCommand(
        String startStarportCode,
        String destinationStarportCode,
        String customerCode,
        String shipCode,
        ShipClass shipClass,
        Instant startAt,
        Instant endAt,
        boolean requestRoute) {

    public enum ShipClass {
        SCOUT,
        FREIGHTER,
        CRUISER,
        UNKNOWN
    }
}
