package com.galactic.starport.repository;

import com.galactic.starport.service.DockingBay;
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
import java.time.Instant;
import java.util.Objects;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(
        name = "docking_bay",
        indexes = {@Index(name = "idx_docking_bay_starport_class", columnList = "starport_id, ship_class, status")})
@Getter
@NoArgsConstructor(access = lombok.AccessLevel.PROTECTED)
class DockingBayEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "docking_bay_id_seq_gen")
    @SequenceGenerator(name = "docking_bay_id_seq_gen", sequenceName = "docking_bay_id_seq", allocationSize = 10)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "starport_id", nullable = false)
    private StarportEntity starport;

    @Column(name = "bay_label")
    private String bayLabel;

    @Column(name = "ship_class")
    @Enumerated(EnumType.STRING)
    private ShipClass shipClass;

    @Column(name = "status")
    private String status;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    public DockingBayEntity(StarportEntity starport, DockingBay dockingBay) {
        this.id = dockingBay.getId();
        this.starport = starport;
        this.bayLabel = dockingBay.getBayLabel();
        this.shipClass = ShipClass.valueOf(dockingBay.getShipClass().name());
        this.status = dockingBay.getStatus();
    }

    public DockingBay toModel() {
        return DockingBay.builder()
                .id(this.id)
                .starportId(this.starport.getId())
                .bayLabel(this.bayLabel)
                .shipClass(DockingBay.ShipClass.valueOf(this.shipClass.name()))
                .status(this.status)
                .createdAt(this.createdAt)
                .updatedAt(this.updatedAt)
                .build();
    }

    enum ShipClass {
        SCOUT,
        FREIGHTER,
        CRUISER,
        UNKNOWN
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof DockingBayEntity that)) return false;
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
