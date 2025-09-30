package com.galactic.starport.api.dto;

import java.time.Instant;

public record ReservationCreateRequest(
        String shipId, ShipClass shipClass, Instant startAt, Instant endAt, boolean requestRoute, String originPortId) {

    public enum ShipClass {
        SCOUT,
        FREIGHTER,
        CRUISER,
        UNKNOWN
    }
}
