package com.galactic.starport.repository;

import com.galactic.starport.domain.Customer;
import com.galactic.starport.repository.mapper.CustomerMapper;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CustomerRepository extends JpaRepository<CustomerEntity, Long> {
    Optional<CustomerEntity> findByCustomerCode(String customerCode);

    default Optional<Customer> findDomainByCustomerCode(String customerCode) {
        return findByCustomerCode(customerCode).map(CustomerMapper::toDomain);
    }
}
