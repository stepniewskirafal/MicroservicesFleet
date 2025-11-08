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
        // Jedna obserwacja opasująca cały proces rezerwacji
        Observation observation = Observation.start("reservations.reserve", observationRegistry);
        boolean success = false;

        try (Observation.Scope scope = observation.openScope()) {
            StarportEntity starport = starportRepository
                    .findByCode(command.destinationStarportCode())
                    .orElseThrow(() -> new StarportNotFoundException(command.destinationStarportCode()));

            validateReservationCommandService.validate(command);

            Reservation newReservation = persistenceService.allocateHold(command, starport);
            newReservation.setFeeCharged(feeCalculatorService.calculateFee(newReservation));

            Optional<Reservation> result = routePlannerService.addRoute(command, newReservation, starport);
            success = result.isPresent();
            return result;
        } catch (RuntimeException ex) {
            // zarejestruj błąd i oznacz tagami
            observation.error(ex);
            observation.lowCardinalityKeyValue("status", "error")
                    .lowCardinalityKeyValue("error", ex.getClass().getSimpleName());
            throw ex;
        } finally {
            if (success) {
                observation.lowCardinalityKeyValue("status", "success")
                        .lowCardinalityKeyValue("error", "none");
            }
            observation.stop();
        }
    }
}
