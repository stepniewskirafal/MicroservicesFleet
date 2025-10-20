package com.galactic.starport.service;

import com.galactic.starport.controller.ReservationResponse;
import com.galactic.starport.repository.*;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class HoldReservationService {
    private final ReservationRepository reservationRepository;
    private final StarportRepository starportRepository;
    private final DockingBayRepository dockingBayRepository;

    @Transactional
    public Optional<ReservationResponse> allocateHold(ReserveBayCommand command) {
        validateStarportExists(command.starportCode());

        final DockingBayEntity dockingBayEntity = dockingBayRepository
                .findFreeBay(command.starportCode(), command.shipClass().name(), command.startAt(), command.endAt())
                .orElseThrow(() -> new NoDockingBaysAvailableException(
                        command.starportCode(), command.startAt(), command.endAt()));

        Reservation reservationHold = Reservation.builder()
                .dockingBayId(dockingBayEntity.getId())
                .customerId(command.customerId())
                .shipId(command.shipId())
                .shipClass(Reservation.ShipClass.valueOf(command.shipClass().name()))
                .startAt(command.startAt())
                .endAt(command.endAt())
                .status(Reservation.ReservationStatus.HOLD)
                .build();
        final ReservationEntity savedReservation = reservationRepository.save(new ReservationEntity(reservationHold));

        return Optional.of(ReservationResponse.builder()
                .reservationId(savedReservation.getId())
                .starportCode(command.starportCode())
                .bayNumber(savedReservation.getDockingBayId())
                .startAt(savedReservation.getStartAt())
                .endAt(savedReservation.getEndAt())
                .feeCharged(savedReservation.getFeeCharged())
                .build());
    }

    private void validateStarportExists(String starportCode) {
        final boolean starportExists = starportRepository.existsByCode(starportCode);

        if (!starportExists) {
            throw new StarportNotFoundException("Starport %s not found".formatted(starportCode));
        }
    }
}
