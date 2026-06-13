package com.galactic.starport.repository;

import com.galactic.starport.service.Reservation;
import com.galactic.starport.service.ReserveBayCommand;
import com.galactic.starport.service.Route;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;

public interface StarportPersistenceFacade {
    Long createHoldReservation(ReserveBayCommand command);

    boolean starportExistsByCode(String code);

    /** The bounded set of known starport codes — used to whitelist the {@code starport} metric tag. */
    Set<String> findAllStarportCodes();

    boolean reservationExistsById(Long reservationId);

    Optional<Reservation> confirmReservation(Long reservationId, BigDecimal calculatedFee, Route route);

    /** Release a single HOLD (compensation when confirm fails after the hold was committed). No-op if not HOLD. */
    void cancelHold(Long reservationId);

    /** Cancel all HOLDs created before {@code cutoff} that were never confirmed. Returns the count reaped. */
    int reapStaleHolds(Instant cutoff);
}
