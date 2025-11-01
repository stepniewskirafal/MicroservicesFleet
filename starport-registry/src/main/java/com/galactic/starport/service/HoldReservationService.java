package com.galactic.starport.service;

import com.galactic.starport.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class HoldReservationService {
    private final CustomerRepository customerRepository;
    private final ShipRepository shipRepository;
    private final StarportRepository starportRepository;
    private final DockingBayRepository dockingBayRepository;
    private final ReservationRepository reservationRepository;

    @Transactional
    public Reservation allocateHold(ReserveBayCommand command, StarportEntity starport) {
        CustomerEntity customer = customerRepository
                .findByCustomerCode(command.customerCode())
                .orElseThrow(() -> new CustomerNotFoundException(command.customerCode()));

        ShipEntity ship = shipRepository
                .findByShipCode(command.shipCode())
                .orElseThrow(() -> new ShipNotFoundException(command.shipCode()));

        DockingBayEntity bay = dockingBayRepository
                .findFreeBay(
                        command.startStarportCode(), command.shipClass().name(), command.startAt(), command.endAt())
                .orElseThrow(() -> new NoDockingBaysAvailableException(
                        command.destinationStarportCode(),
                        command.shipClass().name(),
                        command.startAt(),
                        command.endAt()));

        ReservationEntity hold = new ReservationEntity(starport, bay, customer, ship, command);

        ReservationEntity saved = reservationRepository.save(hold);
        log.info(
                "HOLD created: reservationId={}, starport={}, bay={}, ship={}, customer={}, window=[{}..{}]",
                saved.getId(),
                starport.getCode(),
                bay.getId(),
                ship.getShipCode(),
                customer.getCustomerCode(),
                command.startAt(),
                command.endAt());

        return Reservation.builder()
                .id(saved.getId())
                .starport(starport.toModel())
                .dockingBay(bay.toModel())
                .customer(customer.toModel())
                .ship(ship.toModel())
                .startAt(saved.getStartAt())
                .endAt(saved.getEndAt())
                .status(Reservation.ReservationStatus.valueOf(saved.getStatus().name()))
                .build();
    }
}
