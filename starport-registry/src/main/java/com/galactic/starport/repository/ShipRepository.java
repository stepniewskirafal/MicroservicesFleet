package com.galactic.starport.repository;

import com.galactic.starport.domain.Customer;
import com.galactic.starport.domain.Ship;
import com.galactic.starport.repository.mapper.ShipMapper;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ShipRepository extends JpaRepository<ShipEntity, Long> {
    Optional<ShipEntity> findByShipCode(String shipCode);

    default Optional<Ship> findDomainByShipCode(String shipCode, Customer customer) {
        return findByShipCode(shipCode).map(entity -> ShipMapper.toDomain(entity, customer));
    }
}
