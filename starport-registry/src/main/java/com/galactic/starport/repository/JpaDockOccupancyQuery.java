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
                               COUNT(DISTINCT CASE
                                                WHEN r.start_at <= NOW() AND r.end_at > NOW()
                                                THEN r.docking_bay_id
                                              END) AS occupied,
                               COUNT(DISTINCT db.id) AS total
                        FROM starport s
                        JOIN docking_bay db ON db.starport_id = s.id
                        LEFT JOIN reservation r ON r.docking_bay_id = db.id
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
