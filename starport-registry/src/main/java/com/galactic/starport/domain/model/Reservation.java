package com.galactic.starport.domain.model;

import com.galactic.starport.domain.enums.ReservationStatus;
import com.galactic.starport.domain.enums.ShipClass;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(
        name = "reservation",
        indexes = {@Index(name = "ix_reservation_bay_start", columnList = "docking_bay_id,start_at")})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Reservation {

    @Id
    @GeneratedValue
    private UUID id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "docking_bay_id", nullable = false)
    private DockingBay dockingBay;

    @Column(name = "ship_id", nullable = false, length = 128)
    private String shipId;

    @Enumerated(EnumType.STRING)
    @Column(name = "ship_class", nullable = false, length = 32)
    private ShipClass shipClass;

    @Embedded
    private TimeRange period;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "status", nullable = false, columnDefinition = "reservation_status")
    private ReservationStatus status;

    @Column(name = "fee_amount", precision = 19, scale = 4)
    private BigDecimal feeAmount;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @OneToMany(mappedBy = "reservation", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private List<Route> routes = new ArrayList<>();

    @PrePersist
    void prePersist() {
        if (period != null) period.validate();
        if (dockingBay != null && shipClass != null && dockingBay.getShipClass() != shipClass) {
            throw new IllegalStateException("Reservation.shipClass must equal DockingBay.shipClass");
        }
        createdAt = Instant.now();
        updatedAt = createdAt;
    }

    @PreUpdate
    void preUpdate() {
        if (period != null) period.validate();
        updatedAt = Instant.now();
    }

    public Instant getStartAt() {
        return period != null ? period.getStartAt() : null;
    }

    public Instant getEndAt() {
        return period != null ? period.getEndAt() : null;
    }

    public void confirmReservation(Route route, BigDecimal fee) {
        this.status = ReservationStatus.CONFIRMED;
        this.feeAmount = fee;
        if (this.routes == null || this.routes.isEmpty()) {
            this.routes = new ArrayList<>();
            routes.add(route);
        }else {
            this.routes.add(route);
        }
        route.setActive(true);
        route.setReservation(this);
    }
}
