package com.galactic.starport.repository;

import com.galactic.starport.service.ReserveBayCommand;
import com.galactic.starport.service.Route;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(
        name = "reservation",
        indexes = {@Index(name = "idx_reservation_bay_time", columnList = "docking_bay_id, start_at, end_at")})
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
        this.feeCharged = calculatedFee;
        if (route != null) {
            this.route = new RouteEntity(route, reservationId);
        } else {
            this.route = null;
        }
        this.status = ReservationStatus.CONFIRMED;
        this.updatedAt = Instant.now();
    }

    public enum ReservationStatus {
        HOLD,
        CONFIRMED,
        CANCELLED
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ReservationEntity that)) return false;
        return id != null && id.equals(that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
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
