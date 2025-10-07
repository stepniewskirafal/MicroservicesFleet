package com.galactic.starport.application.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.galactic.starport.application.command.ReserveBayCommand;
import com.galactic.starport.application.event.ReservationEventMapper;
import com.galactic.starport.application.service.tariff.TariffPolicy;
import com.galactic.starport.domain.enums.ReservationStatus;
import com.galactic.starport.domain.exception.NoDockingBaysAvailableException;
import com.galactic.starport.domain.exception.RepositoryUnavailableException;
import com.galactic.starport.domain.exception.StarportNotFoundException;
import com.galactic.starport.domain.model.DockingBay;
import com.galactic.starport.domain.model.Reservation;
import com.galactic.starport.domain.model.Starport;
import com.galactic.starport.domain.model.TimeRange;
import com.galactic.starport.domain.port.OutboxPort;
import com.galactic.starport.domain.port.StarportGateway;
import java.math.BigDecimal;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class ReservationService {

    private final StarportGateway starportGateway;
    private final OutboxPort outboxPort;
    private final TariffPolicy tariffPolicy;
    private final ObjectMapper objectMapper;

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

// 2) Mapowanie → payload aplikacyjny (Application), serializacja do JSON:
        var payload = ReservationEventMapper.toReservationCreated(saved); // <— poprawiona nazwa i użycie 'saved'
        String json;
        try {
            json = objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize event payload", e);
        }

// 3) Zapis do OUTBOX (ten sam @Transactional)
        outboxPort.save(
                "ReservationCreated",
                "reservationCreated-out-0",                  // binding z application.yml
                saved.getDockingBay().getStarport().getId().toString(), // messageKey jako String
                json,
                Map.of(
                        "partitionKey", saved.getDockingBay().getStarport().getId().toString(), // też String
                        "contentType", "application/json"
                )
        );

        return Optional.of(r);
    }
}
