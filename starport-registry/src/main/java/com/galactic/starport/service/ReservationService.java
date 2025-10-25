package com.galactic.starport.service;

import com.galactic.starport.repository.DockingBayEntity;
import com.galactic.starport.repository.DockingBayRepository;
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

    public Optional<Reservation> reserveBay(ReserveBayCommand command) {
        validateReservationCommandService.validate(command);
        Reservation newReservation = persistenceService.allocateHold(command, getFreeDockingBay(command));
        newReservation.setFeeCharged(feeCalculatorService.calculateFee(newReservation));

        newReservation = routePlannerService.confirmReservation(command, newReservation);

        return Optional.of(newReservation);
    }

    private DockingBayEntity getFreeDockingBay(ReserveBayCommand command) {
        return dockingBayRepository
                .findFreeBay(command.starportCode(), command.shipClass().name(), command.startAt(), command.endAt())
                .orElseThrow(() -> new NoDockingBaysAvailableException(
                        command.starportCode(), command.startAt(), command.endAt()));
    }
}
