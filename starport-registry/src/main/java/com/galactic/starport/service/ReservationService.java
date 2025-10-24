package com.galactic.starport.service;

import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ReservationService {

    private final HoldReservationService persistenceService;

    public Optional<Reservation> reserveBay(ReserveBayCommand command) {
        return persistenceService.allocateHold(command);
    }
}
