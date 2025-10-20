package com.galactic.starport.repository;

import com.galactic.starport.service.Reservation;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "reservation")
@NoArgsConstructor
@Getter
public class ReservationEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "docking_bay_id")
    private Long dockingBayId;

    @Column(name = "customer_id")
    private Long customerId;

    @Column(name = "ship_id")
    private String shipId;

    @Enumerated(EnumType.STRING)
    @Column(name = "ship_class")
    private ShipClass shipClass;

    @Column(name = "start_at")
    private Instant startAt;

    @Column(name = "end_at")
    private Instant endAt;

    @Column(name = "fee_charged")
    private BigDecimal feeCharged;

    @Enumerated(EnumType.STRING)
    @Column(name = "status")
    private ReservationStatus status;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    public ReservationEntity(Reservation reservation) {
        this.dockingBayId = reservation.getDockingBayId();
        this.customerId = reservation.getCustomerId();
        this.shipId = reservation.getShipId();
        this.shipClass = ShipClass.valueOf(reservation.getShipClass().name());
        this.startAt = reservation.getStartAt();
        this.endAt = reservation.getEndAt();
        this.feeCharged = reservation.getFeeCharged();
        this.status = ReservationStatus.valueOf(reservation.getStatus().name());
    }

    enum ShipClass {
        SCOUT,
        FREIGHTER,
        CRUISER,
        UNKNOWN
    }

    enum ReservationStatus {
        HOLD,
        CONFIRMED,
        CANCELLED
    }
}
