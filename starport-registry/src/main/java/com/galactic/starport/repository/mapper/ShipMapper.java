package com.galactic.starport.repository.mapper;

import com.galactic.starport.domain.Customer;
import com.galactic.starport.domain.Ship;
import com.galactic.starport.repository.CustomerEntity;
import com.galactic.starport.repository.ShipEntity;

public final class ShipMapper {

    private ShipMapper() {}

    public static Ship toDomain(ShipEntity entity, Customer customer) {
        if (entity == null) {
            return null;
        }

        return Ship.builder()
                .id(entity.getId())
                .customer(customer)
                .shipCode(entity.getShipCode())
                .shipClass(entity.getShipClass() != null
                        ? Ship.ShipClass.valueOf(entity.getShipClass().name())
                        : null)
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .build();
    }

    public static void updateEntityFromDomain(Ship ship, ShipEntity entity, CustomerEntity customerEntity) {
        if (ship == null || entity == null) {
            return;
        }

        entity.setId(ship.getId());
        entity.setCustomer(customerEntity);
        entity.setShipCode(ship.getShipCode());
        entity.setShipClass(ship.getShipClass() != null
                ? ShipEntity.ShipClass.valueOf(ship.getShipClass().name())
                : null);
        entity.setCreatedAt(ship.getCreatedAt());
        entity.setUpdatedAt(ship.getUpdatedAt());
    }
}
