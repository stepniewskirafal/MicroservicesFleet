package com.galactic.starport.repository;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "customer")
@NoArgsConstructor(access = lombok.AccessLevel.PROTECTED)
@Getter
@Setter
public class CustomerEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "customer_id_seq_gen")
    @SequenceGenerator(name = "customer_id_seq_gen", sequenceName = "customer_bay_id_seq", allocationSize = 10)
    private Long id;

    @Column(name = "customer_code")
    private String customerCode;

    @Column(name = "name")
    private String name;

    @OneToMany(mappedBy = "customer", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private List<ShipEntity> ships = new ArrayList<>();

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;
}
