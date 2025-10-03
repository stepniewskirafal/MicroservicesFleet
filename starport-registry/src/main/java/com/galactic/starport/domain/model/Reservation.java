package com.galactic.starport.domain.model;

import com.galactic.starport.domain.enums.ReservationStatus;
import com.galactic.starport.domain.enums.ShipClass;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
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

    /** Kompozycja: mapuje się bezpośrednio na kolumny start_at / end_at */
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

    // --- lifecycle hooks ---

    @PrePersist
    void prePersist() {
        if (period != null) period.validate();
        // prosta walidacja spójności klasy statku z zatoką
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

    // --- helpers ---

    public Instant getStartAt() {
        return period != null ? period.getStartAt() : null;
    }

    public Instant getEndAt() {
        return period != null ? period.getEndAt() : null;
    }

    public long durationHours() {
        return Duration.between(getStartAt(), getEndAt()).toHours();
    }
}
