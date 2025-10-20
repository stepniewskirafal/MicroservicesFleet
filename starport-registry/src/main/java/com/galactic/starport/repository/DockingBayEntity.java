package com.galactic.starport.repository;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.Getter;

@Entity
@Table(name = "docking_bay")
@Getter
public class DockingBayEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "starport_id")
    private Long starportId;

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

    enum ShipClass {
        SCOUT,
        FREIGHTER,
        CRUISER,
        UNKNOWN
    }
}
