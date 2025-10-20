package com.galactic.starport.service;

import java.math.BigDecimal;
import java.time.Instant;
import lombok.Builder;
import lombok.Getter;

@Builder
@Getter
public class Reservation {
    private Long id;
    private Long dockingBayId;
    private Long customerId;
    private String shipId;
    private ShipClass shipClass;
    private Instant startAt;
    private Instant endAt;
    private BigDecimal feeCharged;
    private ReservationStatus status;

    public enum ShipClass {
        SCOUT,
        FREIGHTER,
        CRUISER,
        UNKNOWN
    }

    public enum ReservationStatus {
        HOLD,
        CONFIRMED,
        CANCELLED
    }
}
