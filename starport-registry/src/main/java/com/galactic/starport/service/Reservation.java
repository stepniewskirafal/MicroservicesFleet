package com.galactic.starport.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
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
    private List<Route> routes;

    public void confirmReservationWithRoute(Route route, BigDecimal fee) {
        status = ReservationStatus.CONFIRMED;
        feeCharged = fee;
        addRoute(route);
    }

    private void addRoute(Route route) {
        if (this.routes == null) {
            this.routes = new ArrayList<>();
        }
        this.routes.add(route);
    }

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
