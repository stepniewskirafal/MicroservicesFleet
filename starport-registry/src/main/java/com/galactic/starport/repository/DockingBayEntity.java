package com.galactic.starport.repository;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "docking_bay")
@Getter
@Setter
@NoArgsConstructor(access = lombok.AccessLevel.PROTECTED)
public class DockingBayEntity {

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

    enum ShipClass {
        SCOUT,
        FREIGHTER,
        CRUISER,
        UNKNOWN
    }
}
