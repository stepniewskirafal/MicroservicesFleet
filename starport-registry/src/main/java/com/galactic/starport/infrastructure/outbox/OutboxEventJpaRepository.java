package com.galactic.starport.infrastructure.outbox;

import jakarta.persistence.LockModeType;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.*;
import org.springframework.stereotype.Repository;

@Repository
public interface OutboxEventJpaRepository extends JpaRepository<OutboxEventEntity, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(
            """
       select e from OutboxEventEntity e
       where e.status = com.galactic.starport.infrastructure.outbox.OutboxStatus.PENDING
       order by e.createdAt asc
    """)
    List<OutboxEventEntity> fetchBatchPending(Pageable page);
}
