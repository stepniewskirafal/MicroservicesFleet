package com.galactic.starport.service;

import com.galactic.starport.repository.StarportEntity;
import com.galactic.starport.repository.StarportRepository;
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

    public Optional<Reservation> reserveBay(ReserveBayCommand command) {
        StarportEntity starport = starportRepository
                .findByCode(command.destinationStarportCode())
                .orElseThrow(() -> new StarportNotFoundException(command.destinationStarportCode()));
        validateReservationCommandService.validate(command);
        Reservation newReservation = persistenceService.allocateHold(command, starport);
        newReservation.setFeeCharged(feeCalculatorService.calculateFee(newReservation));

        return routePlannerService.addRoute(command, newReservation, starport);
    }
}
