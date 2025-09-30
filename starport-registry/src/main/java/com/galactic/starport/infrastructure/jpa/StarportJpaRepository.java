package com.galactic.starport.infrastructure.jpa;

import com.galactic.starport.domain.model.DockingBay;
import com.galactic.starport.domain.model.Starport;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface StarportJpaRepository extends JpaRepository<Starport, UUID> {

    Optional<Starport> findByCode(String code);

    @Query(
            value =
                    """
        SELECT db.*
        FROM docking_bay db
        JOIN starport sp ON sp.id = db.starport_id
        WHERE sp.code = :starportCode
          AND db.ship_class = :shipClass
          AND db.status = 'ACTIVE'
          AND NOT EXISTS (
               SELECT 1
               FROM reservation r
               WHERE r.docking_bay_id = db.id
                 AND r.status <> 'CANCELLED'        -- rezerwacje aktywne blokują slot
                 AND r.start_at < :endAt            -- zachodzi część wspólna
                 AND r.end_at   > :startAt
          )
        ORDER BY db.id
        LIMIT 1
        """,
            nativeQuery = true)
    Optional<DockingBay> findFirstFreeBay(
            @Param("starportCode") String starportCode,
            @Param("shipClass") String shipClass,
            @Param("startAt") Instant startAt,
            @Param("endAt") Instant endAt);
}
