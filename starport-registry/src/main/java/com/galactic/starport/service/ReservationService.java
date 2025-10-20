package com.galactic.starport.service;

import com.galactic.starport.controller.ReservationResponse;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ReservationService {

    private final HoldReservationService persistenceService;

    public Optional<ReservationResponse> reserveBay(ReserveBayCommand command) {
        return persistenceService.allocateHold(command);
    }
}
