package com.galactic.starport.repository;

import com.galactic.starport.service.Customer;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.List;

import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "customer")
@NoArgsConstructor(access = lombok.AccessLevel.PROTECTED)
@Getter
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
    private List<ShipEntity> ships;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    public CustomerEntity(Customer customer) {
        this.id = customer.getId();
        this.customerCode = customer.getCustomerCode();
        this.name = customer.getName();
    }

    public Customer toDomain() {
        return Customer.builder()
                .id(this.id)
                .customerCode(this.customerCode)
                .name(this.name)
                .createdAt(this.createdAt)
                .updatedAt(this.updatedAt)
                .build();
    }
}
