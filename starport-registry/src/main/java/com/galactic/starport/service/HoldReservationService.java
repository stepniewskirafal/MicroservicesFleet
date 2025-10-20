package com.galactic.starport.service;

import com.galactic.starport.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class HoldReservationService {
    private final ReservationRepository reservationRepository;
    private final ShipRepository shipRepository;

    private final CustomerRepository customerRepository;

    @Transactional
    public Reservation allocateHold(
            ReserveBayCommand command, DockingBayEntity freeDockingBay, StarportEntity starportEntity) {
        CustomerEntity customerEntity =
                customerRepository.findByCustomerCode(command.customerCode()).get();
        ShipEntity shipEntity =
                shipRepository.findByShipCode(command.shipCode()).get();

        var reservationHold = Reservation.builder()
                .dockingBay(freeDockingBay.toDomain())
                .customer(customerEntity.toDomain())
                .ship(shipEntity.toDomain(customerEntity))
                .startAt(command.startAt())
                .endAt(command.endAt())
                .status(Reservation.ReservationStatus.HOLD)
                .build();
        final ReservationEntity savedReservationEntity =
                reservationRepository.save(new ReservationEntity(reservationHold, starportEntity));
        log.info("Saved reservation with id {} in HOLD status.", savedReservationEntity.getId());

        return toDomain(savedReservationEntity, customerEntity);
    }

    private Reservation toDomain(ReservationEntity entity, CustomerEntity customerEntity) {
        return Reservation.builder()
                .id(entity.getId())
                .dockingBay(entity.getDockingBay().toDomain())
                .customer(entity.getCustomer().toDomain())
                .ship(entity.getShip().toDomain(customerEntity))
                .startAt(entity.getStartAt())
                .endAt(entity.getEndAt())
                .feeCharged(entity.getFeeCharged())
                .status(Reservation.ReservationStatus.valueOf(entity.getStatus().name()))
                .build();
    }
}
