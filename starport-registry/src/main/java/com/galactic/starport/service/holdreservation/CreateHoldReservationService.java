package com.galactic.starport.service.holdreservation;

import com.galactic.starport.repository.StarportPersistenceFacade;
import com.galactic.starport.service.NoDockingBaysAvailableException;
import com.galactic.starport.service.ReserveBayCommand;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
class CreateHoldReservationService implements HoldReservationFacade {
    private static final String OBSERVATION_NAME = "reservations.hold.allocate";
    private static final String METRIC_CAPACITY_REJECTED = "reservations.capacity.rejected";
    private final StarportPersistenceFacade persistenceFacade;
    private final ObservationRegistry observationRegistry;
    private final MeterRegistry meterRegistry;

    CreateHoldReservationService(
            StarportPersistenceFacade persistenceFacade,
            ObservationRegistry observationRegistry,
            MeterRegistry meterRegistry) {
        this.persistenceFacade = persistenceFacade;
        this.observationRegistry = observationRegistry;
        this.meterRegistry = meterRegistry;
    }

    @Override
    public Long createHoldReservation(ReserveBayCommand command) {
        return Observation.createNotStarted(OBSERVATION_NAME, observationRegistry)
                .lowCardinalityKeyValue("starport", command.destinationStarportCode())
                .lowCardinalityKeyValue("shipClass", command.shipClass().name())
                .observe(() -> {
                    try {
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
                    } catch (NoDockingBaysAvailableException e) {
                        meterRegistry.counter(METRIC_CAPACITY_REJECTED,
                                "starport", command.destinationStarportCode(),
                                "shipClass", command.shipClass().name())
                                .increment();
                        throw e;
                    }
                });
    }
}
