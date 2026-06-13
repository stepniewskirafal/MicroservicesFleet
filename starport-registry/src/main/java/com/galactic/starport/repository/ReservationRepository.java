package com.galactic.starport.repository;

import com.galactic.starport.repository.ReservationEntity.ReservationStatus;
import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface ReservationRepository extends JpaRepository<ReservationEntity, Long> {

    @EntityGraph(attributePaths = {"starportEntity", "dockingBay", "customer", "ship", "route"})
    Optional<ReservationEntity> findById(Long id);

    /**
     * Bulk-cancel HOLD reservations that were never confirmed before {@code cutoff} — the reaper's
     * backstop for orphaned holds left by a crash between HOLD and CONFIRM. Returns the row count.
     */
    @Modifying
    // Bulk JPQL bypasses @Version, so bump it explicitly to keep optimistic-lock state consistent.
    @Query("update ReservationEntity r set r.status = :cancelled, r.updatedAt = :now, r.version = r.version + 1 "
            + "where r.status = :hold and r.createdAt < :cutoff")
    int cancelStaleHolds(
            @Param("hold") ReservationStatus hold,
            @Param("cancelled") ReservationStatus cancelled,
            @Param("cutoff") Instant cutoff,
            @Param("now") Instant now);
}
