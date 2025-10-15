package com.galactic.starport.application.command;

import com.galactic.starport.domain.enums.ShipClass;
import java.time.Instant;

public record ReserveBayCommand(
        String starportCode,
        String shipId,
        ShipClass shipClass,
        Instant startAt,
        Instant endAt,
        boolean requestRoute,
        String originPortId) {}
