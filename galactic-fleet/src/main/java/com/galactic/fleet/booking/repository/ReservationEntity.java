package com.galactic.fleet.booking.repository;

import com.galactic.fleet.booking.service.ReserveBayCommand;
import com.galactic.fleet.booking.service.Route;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
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
import jakarta.persistence.Version;
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

    // Optimistic lock. A reservation is written once today (ADR-0043: no HOLD, no reaper), so nothing
    // competes for it yet; the column stays for the first feature that updates a reservation.
    @Version
    @Column(nullable = false)
    private Long version;

    @ManyToOne(fetch = FetchType.LAZY, optional = true, cascade = CascadeType.ALL)
    @JoinColumn(name = "route_id", nullable = true)
    private RouteEntity route;

    public ReservationEntity(
            StarportEntity starport,
            DockingBayEntity bay,
            CustomerEntity customer,
            ShipEntity ship,
            ReserveBayCommand command,
            BigDecimal feeCharged) {
        this.starportEntity = starport;
        this.dockingBay = bay;
        this.customer = customer;
        this.ship = ship;
        this.startAt = command.startAt();
        this.endAt = command.endAt();
        this.feeCharged = feeCharged;
        // A reservation is born CONFIRMED: bay, fee and route are all known before the insert (ADR-0043).
        this.status = ReservationStatus.CONFIRMED;
    }

    /** Attaches the planned route; call after the reservation has its id. No-op for {@code null}. */
    void attachRoute(Route route) {
        this.route = route == null ? null : new RouteEntity(route, this.id);
    }

    public enum ReservationStatus {
        CONFIRMED,
        // No code path cancels a reservation today; the value stays because the free-bay query
        // already treats a CANCELLED reservation as not occupying its bay.
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
