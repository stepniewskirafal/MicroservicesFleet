package com.galactic.starport.repository;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

interface ShipRepository extends JpaRepository<ShipEntity, Long> {
    Optional<ShipEntity> findByShipCode(String shipCode);
}
