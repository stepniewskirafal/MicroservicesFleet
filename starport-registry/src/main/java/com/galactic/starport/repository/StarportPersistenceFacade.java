package com.galactic.starport.repository;

import com.galactic.starport.service.*;
import java.math.BigDecimal;
import java.util.Optional;

public interface StarportPersistenceFacade {
    Long createHoldReservation(ReserveBayCommand command);

    boolean starportExistsByCode(String code);

    boolean reservationExistsById(Long reservationId);

    Optional<Reservation> confirmReservation(Long reservationId, BigDecimal calculatedFee, Route route);
}
