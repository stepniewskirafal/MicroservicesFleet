package com.galactic.starport.service;

import java.math.BigDecimal;
import java.time.Instant;
import lombok.Builder;
import lombok.Getter;

@Builder(toBuilder = true)
@Getter
public class Reservation {
    private Long id;
    private Starport starport;
    private DockingBay dockingBay;
    private Customer customer;
    private Ship ship;
    private Instant startAt;
    private Instant endAt;
    private BigDecimal feeCharged;
    private ReservationStatus status;
    private Route route;

    public void setFeeCharged(BigDecimal feeCharged) {
        this.feeCharged = feeCharged;
    }

    public void confirmReservationWithoutRoute() {
        status = ReservationStatus.CONFIRMED;
    }

    public enum ReservationStatus {
        HOLD,
        CONFIRMED,
        CANCELLED
    }
}
