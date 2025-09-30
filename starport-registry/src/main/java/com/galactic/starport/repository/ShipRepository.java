package com.galactic.starport.repository;

import com.galactic.starport.service.ShipNotFoundException;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ShipRepository extends JpaRepository<ShipEntity, Long> {
    Optional<ShipEntity> findByShipCode(String shipCode);

    default ShipEntity requireByShipCode(String shipCode) {
        return findByShipCode(shipCode).orElseThrow(() -> new ShipNotFoundException(shipCode));
    }
}
