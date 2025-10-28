package com.galactic.starport.repository;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "ship")
@NoArgsConstructor(access = lombok.AccessLevel.PROTECTED)
@Getter
@Setter
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

    public enum ShipClass {
        SCOUT,
        FREIGHTER,
        CRUISER,
        UNKNOWN
    }
}
