package com.galactic.starport.repository;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

interface StarportRepository extends JpaRepository<StarportEntity, Long> {
    boolean existsByCode(String code);

    Optional<StarportEntity> findByCode(String code);

    /** All starport codes — a small, bounded set used to whitelist the {@code starport} metric tag. */
    @Query("select s.code from StarportEntity s")
    List<String> findAllCodes();
}
