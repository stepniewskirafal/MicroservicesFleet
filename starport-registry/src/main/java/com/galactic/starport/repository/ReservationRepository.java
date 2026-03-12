package com.galactic.starport.repository;

import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

interface ReservationRepository extends JpaRepository<ReservationEntity, Long> {

    @EntityGraph(attributePaths = {"starportEntity", "dockingBay", "customer", "ship", "route"})
    Optional<ReservationEntity> findById(Long id);
}
