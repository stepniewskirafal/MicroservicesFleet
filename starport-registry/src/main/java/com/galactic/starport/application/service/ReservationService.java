package com.galactic.starport.application.service;

import com.galactic.starport.application.command.ReserveBayCommand;
import com.galactic.starport.application.service.tariff.TariffPolicy;
import com.galactic.starport.domain.enums.ReservationStatus;
import com.galactic.starport.domain.exception.NoDockingBaysAvailableException;
import com.galactic.starport.domain.exception.RepositoryUnavailableException;
import com.galactic.starport.domain.exception.StarportNotFoundException;
import com.galactic.starport.domain.model.DockingBay;
import com.galactic.starport.domain.model.Reservation;
import com.galactic.starport.domain.model.Starport;
import com.galactic.starport.domain.model.TimeRange;
import com.galactic.starport.domain.port.StarportGateway;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ReservationService {

    private final StarportGateway starportGateway;
    private final TariffPolicy tariffPolicy;

    @Transactional
    public Optional<Reservation> reserveBay(ReserveBayCommand command) {
        var range = new TimeRange(command.startAt(), command.endAt());

        final Starport starport;
        try {
            starport = starportGateway
                    .findByCode(command.starportCode())
                    .orElseThrow(
                            () -> new StarportNotFoundException("Starport %s not found".formatted(command.starportCode())));
        } catch (DataAccessException dae) {
            throw new RepositoryUnavailableException("Database error while loading starport", dae);
        }

        final DockingBay freeBay;
        try {
            freeBay = starportGateway
                    .findFirstFreeBay(starport.getCode(), command.shipClass(), range.getStartAt(), range.getEndAt())
                    .orElseThrow(
                            () -> new NoDockingBaysAvailableException(starport.getCode(), range.getStartAt(), range.getEndAt()));
        } catch (DataAccessException dae) {
            throw new RepositoryUnavailableException("Database error while searching free bay", dae);
        }

        long hours = Math.max(
                1, Duration.between(range.getStartAt(), range.getEndAt()).toHours());
        BigDecimal fee = tariffPolicy.calculate(command.shipClass(), hours);

        Reservation r = Reservation.builder()
                .dockingBay(freeBay)
                .shipId(command.shipId())
                .shipClass(command.shipClass())
                .period(range)
                .status(ReservationStatus.CONFIRMED)
                .feeAmount(fee)
                .build();

        final Reservation saved;
        try {
            saved = starportGateway.save(r);
        } catch (DataAccessException dae) {
            throw new RepositoryUnavailableException("Database error while saving reservation", dae);
        }

        /*telemetry.tariffCalculated(
                UUID.randomUUID().toString(),
                Instant.now(),
                saved.getDockingBay().getStarport().getCode(),
                saved.getId(),
                saved.getShipClass(),
                saved.durationHours(),
                tariffPolicy.calculate(saved.getShipClass(), saved.durationHours());
        telemetry.reservationCreated(saved);*/

        return Optional.of(saved);
    }
}
