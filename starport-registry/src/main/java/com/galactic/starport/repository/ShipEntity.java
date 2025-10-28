package com.galactic.starport.repository;

import com.galactic.starport.service.Customer;
import com.galactic.starport.service.Ship;
import jakarta.persistence.*;
import java.time.Instant;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "ship")
@NoArgsConstructor(access = lombok.AccessLevel.PROTECTED)
public class ShipEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "ship_id_seq_gen")
    @SequenceGenerator(name = "ship_id_seq_gen", sequenceName = "ship_id_seq_gen", allocationSize = 10)
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

    public Ship toDomain(Customer customer) {
        return Ship.builder()
                .id(this.id)
                .customer(customer)
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
}
