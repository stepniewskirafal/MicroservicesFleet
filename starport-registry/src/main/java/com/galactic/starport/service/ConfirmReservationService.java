package com.galactic.starport.service;

import com.galactic.starport.repository.StarportPersistenceFacade;
import com.galactic.starport.service.outbox.OutboxFacade;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
class ConfirmReservationService {
    private static final String OBSERVATION_NAME = "reservations.confirm";
    private final ObservationRegistry observationRegistry;
    private final OutboxFacade outboxFacade;
    private final StarportPersistenceFacade persistenceFacade;

    @Transactional
    public Reservation confirmReservation(ReservationCalculation calc, String destinationStarportCode) {
        Observation obs = Observation.createNotStarted(OBSERVATION_NAME, observationRegistry)
                .lowCardinalityKeyValue("eventType", "ReservationConfirmed");
        if (destinationStarportCode != null) {
            obs.lowCardinalityKeyValue("starport", destinationStarportCode);
        }
        return obs.observe(() -> {
            Reservation reservation = persistenceFacade
                    .confirmReservation(calc.reservationId(), calc.calculatedFee(), calc.route())
                    .orElseThrow(() -> {
                        log.error("Failed to confirm reservation with ID: {}.", calc.reservationId());
                        return new ReservationConfirmationException(calc.reservationId());
                    });

            log.info("Reservation with ID: {} confirmed successfully.", calc.reservationId());
            outboxFacade.publishReservationConfirmedEvent(reservation);
            return reservation;
        });
    }
}
