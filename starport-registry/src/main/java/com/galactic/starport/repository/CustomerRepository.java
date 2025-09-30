package com.galactic.starport.repository;

import com.galactic.starport.service.CustomerNotFoundException;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CustomerRepository extends JpaRepository<CustomerEntity, Long> {
    Optional<CustomerEntity> findByCustomerCode(String customerCode);

    default CustomerEntity requireByCustomerCode(String customerCode) {
        return findByCustomerCode(customerCode).orElseThrow(() -> new CustomerNotFoundException(customerCode));
    }
}
