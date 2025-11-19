package com.galactic.starport.repository;

import com.galactic.starport.service.*;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.stream.Stream;
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

    @OneToMany(mappedBy = "reservation", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private List<RouteEntity> routes = new ArrayList<>();

    public ReservationEntity(Reservation reservation, StarportEntity starportEntity) {
        this.id = reservation.getId();
        setDockingBay(reservation.getDockingBay(), starportEntity);
        setCustomer(reservation.getCustomer());
        setShip(reservation.getShip());
        this.startAt = reservation.getStartAt();
        this.endAt = reservation.getEndAt();
        this.feeCharged = reservation.getFeeCharged();
        this.status = ReservationStatus.valueOf(reservation.getStatus().name());
        addRoute(reservation);
    }

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

    private void setShip(Ship ship) {
        if (ship == null) {
            this.ship = null;
        } else if (this.ship == null) {
            this.ship = new ShipEntity(ship, this.customer);
        }
    }

    private void setCustomer(Customer customer) {
        if (customer == null) {
            this.customer = null;
        } else if (this.customer == null) {
            this.customer = new CustomerEntity(customer);
        }
    }

    private void setDockingBay(DockingBay dockingBay, StarportEntity starportEntity) {
        if (dockingBay == null) {
            this.dockingBay = null;
        } else if (this.dockingBay == null) {
            this.dockingBay = new DockingBayEntity(starportEntity, dockingBay);
        }
    }

    private void addRoute(Reservation reservation) {
        Stream.ofNullable(reservation.getRoutes())
                .flatMap(Collection::stream)
                .map(route -> new RouteEntity(route, this))
                .forEach(this.routes::add);
    }

    public void cancelRevervation() {
        this.status = ReservationStatus.CANCELLED;
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
