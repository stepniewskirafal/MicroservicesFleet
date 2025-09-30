package com.galactic.starport.repository;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StarportRepository extends JpaRepository<StarportEntity, Long> {
    boolean existsByCode(String code);

    Optional<StarportEntity> findByCode(String code);
}
