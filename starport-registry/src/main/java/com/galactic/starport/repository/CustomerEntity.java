package com.galactic.starport.repository;

import com.galactic.starport.service.Customer;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.OneToMany;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.Fetch;
import org.hibernate.annotations.FetchMode;

@Entity
@Table(
        name = "customer",
        indexes = {@Index(name = "idx_customer_code", columnList = "customer_code")})
@NoArgsConstructor(access = lombok.AccessLevel.PROTECTED)
@Getter
@Setter
class CustomerEntity {
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

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof CustomerEntity that)) return false;
        return customerCode != null && customerCode.equals(that.customerCode);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(customerCode);
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
