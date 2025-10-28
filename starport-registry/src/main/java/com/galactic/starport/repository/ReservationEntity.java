package com.galactic.starport.repository;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "reservation")
@NoArgsConstructor(access = lombok.AccessLevel.PROTECTED)
@Getter
@Setter
public class ReservationEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "reservation_id_seq_gen")
    @SequenceGenerator(name = "reservation_id_seq_gen", sequenceName = "reservation_id_seq", allocationSize = 10)
    private Long id;

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
