package com.galactic.starport.service;

import com.galactic.starport.domain.DockingBay;
import com.galactic.starport.domain.Reservation;
import com.galactic.starport.domain.ReserveBayCommand;
import com.galactic.starport.repository.DockingBayRepository;
import com.galactic.starport.repository.ReservationPersistenceAdapter;
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
    private final DockingBayRepository dockingBayRepository;
    private final RoutePlannerService routePlannerService;
    private final ReservationPersistenceAdapter reservationPersistenceAdapter;

    public Optional<Reservation> reserveBay(ReserveBayCommand command) {
        validateReservationCommandService.validate(command);
        DockingBay dockingBay = getFreeDockingBay(command);
        Reservation newReservation = persistenceService.allocateHold(command, dockingBay);
        newReservation.setFeeCharged(feeCalculatorService.calculateFee(newReservation));
        Reservation savedReservation = reservationPersistenceAdapter.save(newReservation);

        Optional<Reservation> reservationWithRoute = routePlannerService.addRoute(command, savedReservation);
        return reservationWithRoute;
    }

    private DockingBay getFreeDockingBay(ReserveBayCommand command) {
        return dockingBayRepository
                .findAvailableBay(
                        command.destinationStarportCode(),
                        command.shipClass().name(),
                        command.startAt(),
                        command.endAt())
                .orElseThrow(() ->
                        new NoDockingBaysAvailableException(
                                command.destinationStarportCode(), command.startAt(), command.endAt()));
    }
}
