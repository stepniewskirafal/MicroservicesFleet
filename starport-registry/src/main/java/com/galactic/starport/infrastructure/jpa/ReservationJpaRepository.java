package com.galactic.starport.infrastructure.jpa;

import com.galactic.starport.domain.model.Reservation;
import java.util.UUID;
import org.springframework.data.jpa.repository.*;

public interface ReservationJpaRepository extends JpaRepository<Reservation, UUID> {}
