package com.galactic.starport.service;

import com.galactic.starport.domain.Customer;
import com.galactic.starport.domain.DockingBay;
import com.galactic.starport.domain.Reservation;
import com.galactic.starport.domain.ReserveBayCommand;
import com.galactic.starport.domain.Ship;
import com.galactic.starport.repository.ReservationPersistenceAdapter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class HoldReservationService {
    private final ReservationPersistenceAdapter reservationPersistenceAdapter;

    @Transactional
    public Reservation allocateHold(ReserveBayCommand command, DockingBay dockingBay) {
        Customer customer = reservationPersistenceAdapter.loadCustomerByCode(command.customerCode());
        Ship ship = reservationPersistenceAdapter.loadShipByCode(command.shipCode(), customer);

        Reservation reservationHold = Reservation.builder()
                .dockingBay(dockingBay)
                .customer(customer)
                .ship(ship)
                .startAt(command.startAt())
                .endAt(command.endAt())
                .status(Reservation.ReservationStatus.HOLD)
                .build();

        Reservation savedReservation = reservationPersistenceAdapter.save(reservationHold);
        log.info("Saved reservation with id {} in HOLD status.", savedReservation.getId());

        return savedReservation;
    }
}
