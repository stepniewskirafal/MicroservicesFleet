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
    private final CustomerRepository customerRepository;

    @Transactional
    public Reservation allocateHold(ReserveBayCommand command, DockingBayEntity freeDockingBay) {
        final CustomerEntity customerEntity =
                customerRepository.findByCustomerCode(command.customerCode()).get();
        var reservationHold = Reservation.builder()
                .dockingBayId(freeDockingBay.getId())
                .customerId(customerEntity.getId())
                .shipId(command.shipId())
                .shipClass(Reservation.ShipClass.valueOf(command.shipClass().name()))
                .startAt(command.startAt())
                .endAt(command.endAt())
                .status(Reservation.ReservationStatus.HOLD)
                .build();
        final ReservationEntity saved = reservationRepository.save(new ReservationEntity(reservationHold));
        log.info("Saved reservation with id {} in HOLD status.", saved.getId());

        return toDomain(saved);
    }

    private Reservation toDomain(ReservationEntity entity) {
        return Reservation.builder()
                .id(entity.getId())
                .dockingBayId(entity.getDockingBayId())
                .customerId(entity.getCustomerId())
                .shipId(entity.getShipId())
                .shipClass(Reservation.ShipClass.valueOf(entity.getShipClass().name()))
                .startAt(entity.getStartAt())
                .endAt(entity.getEndAt())
                .feeCharged(entity.getFeeCharged())
                .status(Reservation.ReservationStatus.valueOf(entity.getStatus().name()))
                .build();
    }
}
