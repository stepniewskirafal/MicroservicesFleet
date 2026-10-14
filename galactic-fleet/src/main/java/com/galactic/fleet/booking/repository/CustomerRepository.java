package com.galactic.fleet.booking.repository;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

interface CustomerRepository extends JpaRepository<CustomerEntity, Long> {
    Optional<CustomerEntity> findByCustomerCode(String customerCode);

    boolean existsByCustomerCode(String customerCode);
}
