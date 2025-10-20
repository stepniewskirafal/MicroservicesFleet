package com.galactic.starport.repository;

import org.springframework.data.jpa.repository.JpaRepository;

public interface StarportRepository extends JpaRepository<StarportEntity, Long> {
    boolean existsByCode(String code);
}
