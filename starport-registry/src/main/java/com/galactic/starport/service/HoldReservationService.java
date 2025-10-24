package com.galactic.starport.service;

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
    public Optional<Reservation> allocateHold(ReserveBayCommand command) {
        validateStarportExists(command.starportCode());

        final DockingBayEntity freeBay = dockingBayRepository
                .findFreeBay(command.starportCode(), command.shipClass().name(), command.startAt(), command.endAt())
                .orElseThrow(() -> new NoDockingBaysAvailableException(
                        command.starportCode(), command.startAt(), command.endAt()));

        var reservationHold = Reservation.builder()
                .dockingBayId(freeBay.getId())
                .customerId(command.customerId())
                .shipId(command.shipId())
                .shipClass(Reservation.ShipClass.valueOf(command.shipClass().name()))
                .startAt(command.startAt())
                .endAt(command.endAt())
                .status(Reservation.ReservationStatus.HOLD)
                .build();

        final ReservationEntity saved = reservationRepository.save(new ReservationEntity(reservationHold));

        return Optional.of(toDomain(saved));
    }

    private void validateStarportExists(String starportCode) {
        final boolean starportExists = starportRepository.existsByCode(starportCode);

        if (!starportExists) {
            throw new StarportNotFoundException("Starport %s not found".formatted(starportCode));
        }
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
