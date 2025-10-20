package com.galactic.starport.repository;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "ship")
public class ShipEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "customer_id")
    private Long customerId;

    @Column(name = "ship_id")
    private String shipId;

    @Column(name = "ship_name")
    private String shipName;

    @Column(name = "ship_class")
    private String shipClass;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;
}
