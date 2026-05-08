package com.galactic.starport.repository;

import jakarta.persistence.EntityManager;
import java.util.List;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
class JpaDockOccupancyQuery implements DockOccupancyQuery {

    private final EntityManager em;

    JpaDockOccupancyQuery(EntityManager em) {
        this.em = em;
    }

    @Override
    @Transactional(readOnly = true)
    public List<DockOccupancySnapshot> aggregate() {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery(
                        """
                        SELECT s.code,
                               COUNT(*) FILTER (WHERE db.status <> 'AVAILABLE') AS occupied,
                               COUNT(*) AS total
                        FROM docking_bay db
                        JOIN starport s ON s.id = db.starport_id
                        GROUP BY s.code
                        """)
                .getResultList();
        return rows.stream()
                .map(r -> new DockOccupancySnapshot(
                        (String) r[0],
                        ((Number) r[1]).longValue(),
                        ((Number) r[2]).longValue()))
                .toList();
    }
}
