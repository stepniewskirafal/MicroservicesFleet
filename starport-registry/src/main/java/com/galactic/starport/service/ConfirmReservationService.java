package com.galactic.starport.service;

import com.galactic.starport.repository.StarportPersistenceFacade;
import com.galactic.starport.service.outbox.OutboxFacade;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
class ConfirmReservationService {
    private final OutboxFacade outboxFacade;
    private final StarportPersistenceFacade persistenceFacade;

    @Transactional
    public Reservation confirmReservation(ReservationCalculation reservationCalculation) {
        Optional<Reservation> confirmedReservation = persistenceFacade.confirmReservation(
                reservationCalculation.reservationId(),
                reservationCalculation.calculatedFee(),
                reservationCalculation.route());
        return confirmedReservation
                .map(reservation -> {
                    log.info("Reservation with ID: {} confirmed successfully.", reservationCalculation.reservationId());
                    outboxFacade.publishReservationConfirmedEvent(reservation);
                    return reservation;
                })
                .orElseThrow(() -> {
                    log.error("Failed to confirm reservation with ID: {}.", reservationCalculation.reservationId());
                    return new ReservationConfirmationException(reservationCalculation.reservationId());
                });
    }
}
