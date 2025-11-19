package com.galactic.starport.service;

import com.galactic.starport.repository.StarportPersistenceFacade;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
class ConfirmReservationService {

    private final OutboxAppender outboxAppender;
    private final StarportPersistenceFacade persistenceFacade;

    @Transactional
    public void confirmReservation(ReservationCalculation result) {
        Long confirmed =
                persistenceFacade.confirmReservation(result.reservationId(), result.calculatedFee(), result.route());
        // outboxAppender.appendReservationEvent("ReservationConfirmed", confirmed, route);
        log.info("Confirming reservation with ID: {}", result.reservationId());
    }
}
