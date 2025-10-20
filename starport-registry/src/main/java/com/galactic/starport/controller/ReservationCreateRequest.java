package com.galactic.starport.controller;

import java.time.Instant;

record ReservationCreateRequest(
        Long customerId,
        String shipId,
        ShipClass shipClass,
        Instant startAt,
        Instant endAt,
        boolean requestRoute,
        String originPortId) {

    enum ShipClass {
        SCOUT,
        FREIGHTER,
        CRUISER,
        UNKNOWN
    }
}
