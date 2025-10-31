package com.galactic.starport.repository;

import java.util.List;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface OutboxEventJpaRepository extends JpaRepository<OutboxEventEntity, Long> {
    @Query(
            value =
                    """
              select *
              from event_outbox
              where status = 'PENDING'
              order by created_at
              for update skip locked
              limit :limit
        """,
            nativeQuery = true)
    List<OutboxEventEntity> lockBatchPending(@Param("limit") int limit);
}
