package com.galactic.starport.repository;

import com.galactic.starport.domain.DockingBay;
import com.galactic.starport.repository.mapper.DockingBayMapper;
import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DockingBayRepository extends JpaRepository<DockingBayEntity, Long> {
    @Query(
            value =
                    """
                    select docking_bay.*
                    from starport
                    join docking_bay
                       on starport.id = docking_bay.starport_id
                    where starport.code = :starportCode
                    and docking_bay.status = 'AVAILABLE'
                    and docking_bay.ship_class = :shipClass
                     AND NOT EXISTS (
                       SELECT 1
                       FROM reservation r
                       WHERE docking_bay.id = r.docking_bay_id
                         AND ( r.start_at < :endAt
                         AND r.end_at   > :startAt ))
                    FOR UPDATE SKIP LOCKED
                    LIMIT 1
                    """,
            nativeQuery = true)
    Optional<DockingBayEntity> findFreeBay(
            @Param("starportCode") String starportCode,
            @Param("shipClass") String shipClass,
            @Param("startAt") Instant startAt,
            @Param("endAt") Instant endAt);

    default Optional<DockingBay> findAvailableBay(
            String starportCode, String shipClass, Instant startAt, Instant endAt) {
        return findFreeBay(starportCode, shipClass, startAt, endAt).map(DockingBayMapper::toDomain);
    }
}
