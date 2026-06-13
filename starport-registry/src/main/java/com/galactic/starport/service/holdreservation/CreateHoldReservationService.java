package com.galactic.starport.service.holdreservation;

import com.galactic.starport.repository.StarportPersistenceFacade;
import com.galactic.starport.service.ReserveBayCommand;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.util.Objects;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
class CreateHoldReservationService implements HoldReservationFacade {
    private static final String OBSERVATION_NAME = "reservations.hold.allocate";
    private final StarportPersistenceFacade persistenceFacade;
    private final ObservationRegistry observationRegistry;

    CreateHoldReservationService(
            StarportPersistenceFacade persistenceFacade, ObservationRegistry observationRegistry) {
        this.persistenceFacade = persistenceFacade;
        this.observationRegistry = observationRegistry;
    }

    @Override
    public Long createHoldReservation(ReserveBayCommand command) {
        Objects.requireNonNull(command, "command must not be null");
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

    @Override
    public void cancelHold(Long reservationId) {
        Objects.requireNonNull(reservationId, "reservationId must not be null");
        persistenceFacade.cancelHold(reservationId);
        log.info("HOLD cancelled (compensation): reservationId={}", reservationId);
    }
}
