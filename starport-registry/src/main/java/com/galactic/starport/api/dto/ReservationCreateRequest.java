package com.galactic.starport.api.dto;

import com.galactic.starport.domain.enums.ShipClass;
import java.time.Instant;

public record ReservationCreateRequest(
        String shipId, ShipClass shipClass, Instant startAt, Instant endAt, boolean requestRoute // jeśli true, wołamy B
        ) {}
