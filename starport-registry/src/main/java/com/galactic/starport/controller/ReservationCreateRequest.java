package com.galactic.starport.controller;

import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;

record ReservationCreateRequest(
        @NotNull(message = "Customer ID must not be empty") Long customerId,
        @NotBlank(message = "Ship identifier must not be blank") String shipId,
        @NotNull(message = "Ship class must not be blank") ShipClass shipClass,
        @NotNull(message = "Start time must not be null") @Future(message = "Start time must be in the future")
                Instant startAt,
        @NotNull(message = "End time must not be null") @Future(message = "End time must be in the future")
                Instant endAt,
        @NotNull boolean requestRoute,
        String originPortId) {

    enum ShipClass {
        SCOUT,
        FREIGHTER,
        CRUISER,
        UNKNOWN
    }
}
