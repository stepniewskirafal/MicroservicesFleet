package com.galactic.starport.repository;

import com.galactic.starport.service.Customer;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.Fetch;
import org.hibernate.annotations.FetchMode;

@Entity
@Table(name = "customer")
@NoArgsConstructor(access = lombok.AccessLevel.PROTECTED)
@Getter
@Setter
public class CustomerEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "customer_id_seq_gen")
    @SequenceGenerator(name = "customer_id_seq_gen", sequenceName = "customer_id_seq", allocationSize = 10)
    private Long id;

    @Column(name = "customer_code")
    private String customerCode;

    @Column(name = "name")
    private String name;

    @OneToMany(mappedBy = "customer", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @Fetch(FetchMode.SELECT)
    private List<ShipEntity> ships = new ArrayList<>();

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    public CustomerEntity(Customer customer) {
        this.id = customer.getId();
        this.customerCode = customer.getCustomerCode();
        this.name = customer.getName();
        this.createdAt = customer.getCreatedAt();
        this.updatedAt = customer.getUpdatedAt();
        this.ships = new ArrayList<>();
        if (customer.getShips() != null && !customer.getShips().isEmpty()) {
            this.ships.addAll(customer.getShips().stream()
                    .map(ship -> new ShipEntity(ship, this))
                    .toList());
        }
    }

    public Customer toModel() {
        return Customer.builder()
                .id(this.id)
                .customerCode(this.customerCode)
                .name(this.name)
                .createdAt(this.createdAt)
                .updatedAt(this.updatedAt)
                .build();
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
