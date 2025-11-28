package com.galactic.starport.service;

import com.galactic.starport.repository.StarportPersistenceFacade;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
class CreateHoldReservationService {
    private static final String OBSERVATION_NAME = "reservations.hold.allocate";
    private final StarportPersistenceFacade persistenceFacade;
    private final ObservationRegistry observationRegistry;

    Long createHoldReservation(ReserveBayCommand command) {
        return Observation.createNotStarted(OBSERVATION_NAME, observationRegistry)
                .lowCardinalityKeyValue("starport", command.destinationStarportCode())
                .lowCardinalityKeyValue("shipClass", command.shipClass().name())
                .observe(() -> {
                    Long reservationId = persistenceFacade.createHoldReservation(command);
                    log.info(
                            "HOLD created: reservationId={}, starport={}, ship={}, customer={}, window=[{}..{}]",
                            reservationId,
                            command.destinationStarportCode(),
                            command.shipCode(),
                            command.customerCode(),
                            command.startAt(),
                            command.endAt());
                    return reservationId;
                });
    }
}
