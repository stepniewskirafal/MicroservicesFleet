package com.galactic.starport.repository;

import com.galactic.starport.service.Customer;

final class CustomerMapper {

    private CustomerMapper() {}

    static Customer toDomain(CustomerEntity entity) {
        if (entity == null) {
            return null;
        }
        return entity.toModel();
    }

    static CustomerEntity toEntity(Customer customer) {
        if (customer == null) {
            return null;
        }
        return new CustomerEntity(customer);
    }
}
