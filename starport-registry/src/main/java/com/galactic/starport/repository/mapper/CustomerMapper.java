package com.galactic.starport.repository.mapper;

import com.galactic.starport.domain.Customer;
import com.galactic.starport.repository.CustomerEntity;

public final class CustomerMapper {

    private CustomerMapper() {}

    public static Customer toDomain(CustomerEntity entity) {
        if (entity == null) {
            return null;
        }

        return Customer.builder()
                .id(entity.getId())
                .customerCode(entity.getCustomerCode())
                .name(entity.getName())
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .build();
    }
}
