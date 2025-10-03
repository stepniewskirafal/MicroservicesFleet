package com.galactic.starport.application.service;

import com.galactic.starport.api.error.*;
import com.galactic.starport.application.command.ReserveBayCommand;
import com.galactic.starport.application.event.IncidentRecorded;
import com.galactic.starport.application.port.RoutePlannerPort;
import com.galactic.starport.application.port.TelemetryPort;
import com.galactic.starport.application.service.tariff.TariffPolicy;
import com.galactic.starport.domain.enums.ReservationStatus;
import com.galactic.starport.domain.model.DockingBay;
import com.galactic.starport.domain.model.Reservation;
import com.galactic.starport.domain.model.Starport;
import com.galactic.starport.domain.model.TimeRange;
import com.galactic.starport.domain.port.StarportGateway;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.apache.logging.log4j.util.Strings;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ReservationService {

    private final StarportGateway starportGateway;
    private final TariffPolicy tariffPolicy;
    private final RoutePlannerPort routePlanner;
    private final TelemetryPort telemetry;

    @Transactional
    public Optional<Reservation> reserveBay(ReserveBayCommand command) {
        var range = new TimeRange(command.startAt(), command.endAt());

        final Starport sp;
        try {
            sp = starportGateway
                    .findByCode(command.starportCode())
                    .orElseThrow(
                            () -> new NotFoundException("Starport %s not found".formatted(command.starportCode())));
        } catch (DataAccessException dae) {
            throw new RepositoryUnavailableException("Database error while loading starport", dae);
        }

        final DockingBay freeBay;
        try {
            freeBay = starportGateway
                    .findFirstFreeBay(sp.getCode(), command.shipClass(), range.getStartAt(), range.getEndAt())
                    .orElseThrow(
                            () -> new NoDockingBaysAvailableException("No docking bays available in requested window"));
        } catch (DataAccessException dae) {
            throw new RepositoryUnavailableException("Database error while searching free bay", dae);
        }

        long hours = Math.max(
                1, Duration.between(range.getStartAt(), range.getEndAt()).toHours());
        BigDecimal fee = tariffPolicy.calculate(command.shipClass(), hours);

        telemetry.tariffCalculated(sp.getCode(), null, command.shipClass(), hours, fee);

        Reservation r = Reservation.builder()
                .dockingBay(freeBay)
                .shipId(command.shipId())
                .shipClass(command.shipClass())
                .period(range)
                .status(ReservationStatus.CONFIRMED)
                .feeAmount(fee)
                .build();

        if (command.requestRoute() && Strings.isNotBlank(command.starportCode())) {
            String routeId = routePlanner.requestRoute(command.shipId(), sp.getCode(), command.starportCode());
        }

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

    @Transactional
    public void recordIncident(
            String starportCode, String type, String severity, String description, UUID reservationIdOrNull) {
        IncidentRecorded evt = new IncidentRecorded(
                UUID.randomUUID().toString(),
                Instant.now(),
                starportCode,
                type,
                severity,
                description,
                reservationIdOrNull);
        telemetry.incidentRecorded(evt);
    }
}
