package com.galactic.starport.repository;

import com.galactic.starport.service.Ship;
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
        name = "ship",
        indexes = {@Index(name = "idx_ship_code", columnList = "ship_code")})
@NoArgsConstructor(access = lombok.AccessLevel.PROTECTED)
@Getter
class ShipEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "ship_id_seq_gen")
    @SequenceGenerator(name = "ship_id_seq_gen", sequenceName = "ship_id_seq", allocationSize = 10)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "customer_id", nullable = false)
    private CustomerEntity customer;

    @Column(name = "ship_code")
    private String shipCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "ship_class")
    private ShipClass shipClass;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    public ShipEntity(Ship ship, CustomerEntity customer) {
        this.id = ship.getId();
        this.customer = customer;
        this.shipCode = ship.getShipCode();
        this.shipClass = ShipClass.valueOf(ship.getShipClass().name());
    }

    public Ship toModel() {
        return Ship.builder()
                .id(this.id)
                .customer(customer.toModel())
                .shipCode(this.shipCode)
                .shipClass(Ship.ShipClass.valueOf(this.shipClass.name()))
                .createdAt(this.createdAt)
                .updatedAt(this.updatedAt)
                .build();
    }

    public enum ShipClass {
        SCOUT,
        FREIGHTER,
        CRUISER,
        UNKNOWN
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ShipEntity that)) return false;
        return shipCode != null && shipCode.equals(that.shipCode);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(shipCode);
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
