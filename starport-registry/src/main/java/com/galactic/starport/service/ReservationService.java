package com.galactic.starport.service;

import com.galactic.starport.repository.StarportEntity;
import com.galactic.starport.repository.StarportRepository;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class ReservationService {

    private final HoldReservationService persistenceService;
    private final ValidateReservationCommandService validateReservationCommandService;
    private final FeeCalculatorService feeCalculatorService;
    private final RoutePlannerService routePlannerService;
    private final StarportRepository starportRepository;
    private final ObservationRegistry observationRegistry;

    public Optional<Reservation> reserveBay(ReserveBayCommand command) {
        Observation observation = Observation.createNotStarted("reservations.reserve", observationRegistry)
                .lowCardinalityKeyValue("starport", command.destinationStarportCode())
                //.observe()

                .start();

        try (Observation.Scope scope = observation.openScope()) {
            StarportEntity starport = starportRepository
                    .findByCode(command.destinationStarportCode())
                    .orElseThrow(() -> new StarportNotFoundException(command.destinationStarportCode()));

            validateReservationCommandService.validate(command);

            Reservation newReservation = persistenceService.allocateHold(command, starport);
            newReservation.setFeeCharged(feeCalculatorService.calculateFee(newReservation));

            Optional<Reservation> result = routePlannerService.addRoute(command, newReservation, starport);

            observation.lowCardinalityKeyValue("status", result.isPresent() ? "success" : "no-route")
                    .lowCardinalityKeyValue("error", "none");

            return result;
        } catch (RuntimeException ex) {
            observation.error(ex);
            throw ex;
        } finally {
            observation.stop();
        }
    }
}
