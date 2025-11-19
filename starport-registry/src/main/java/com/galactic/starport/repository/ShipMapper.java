package com.galactic.starport.repository;

import com.galactic.starport.service.Ship;

final class ShipMapper {

    private ShipMapper() {}

    static Ship toDomain(ShipEntity entity) {
        if (entity == null) {
            return null;
        }
        return entity.toModel();
    }

    /**
     * Tworzy encję ShipEntity dla istniejącego CustomerEntity.
     * Zakładamy, że customerEntity jest już zarządzany przez JPA (persist/merge).
     */
    static ShipEntity toEntity(Ship ship, CustomerEntity customerEntity) {
        if (ship == null) {
            return null;
        }
        return new ShipEntity(ship, customerEntity);
    }
}
