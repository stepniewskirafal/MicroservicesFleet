package com.galactic.starport.repository;

import com.galactic.starport.service.*;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "reservation")
@NoArgsConstructor(access = lombok.AccessLevel.PROTECTED)
@Getter
class ReservationEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "reservation_id_seq_gen")
    @SequenceGenerator(name = "reservation_id_seq_gen", sequenceName = "reservation_id_seq", allocationSize = 10)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "starport_id", nullable = false)
    private StarportEntity starportEntity;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "docking_bay_id", nullable = false)
    private DockingBayEntity dockingBay;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "customer_id", nullable = false)
    private CustomerEntity customer;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "ship_id", nullable = false)
    private ShipEntity ship;

    private Instant startAt;

    private Instant endAt;

    private BigDecimal feeCharged;

    @Enumerated(EnumType.STRING)
    private ReservationStatus status;

    private Instant createdAt;

    private Instant updatedAt;

    @ManyToOne(fetch = FetchType.LAZY, optional = true, cascade = CascadeType.ALL)
    @JoinColumn(name = "route_id", nullable = true)
    private RouteEntity route;

    public ReservationEntity(
            StarportEntity starport,
            DockingBayEntity bay,
            CustomerEntity customer,
            ShipEntity ship,
            ReserveBayCommand command) {
        this.starportEntity = starport;
        this.dockingBay = bay;
        this.customer = customer;
        this.ship = ship;
        this.startAt = command.startAt();
        this.endAt = command.endAt();
        this.status = ReservationStatus.HOLD;
    }

    public void confirmReservation(Long reservationId, BigDecimal calculatedFee, Route route) {
        this.id = reservationId;
        this.feeCharged = calculatedFee;
        this.route = new RouteEntity(route);
        this.status = ReservationStatus.CONFIRMED;
        this.updatedAt = Instant.now();
    }

    public enum ReservationStatus {
        HOLD,
        CONFIRMED,
        CANCELLED
    }

    @PrePersist
    void prePersist() {
        createdAt = Instant.now();
        updatedAt = createdAt;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now();
    }
}
