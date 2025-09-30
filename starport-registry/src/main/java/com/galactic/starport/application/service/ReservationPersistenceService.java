package com.galactic.starport.application.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.galactic.starport.application.command.ReserveBayCommand;
import com.galactic.starport.application.event.ReservationEventMapper;
import com.galactic.starport.domain.enums.ReservationStatus;
import com.galactic.starport.domain.exception.NoDockingBaysAvailableException;
import com.galactic.starport.domain.exception.RepositoryUnavailableException;
import com.galactic.starport.domain.exception.StarportNotFoundException;
import com.galactic.starport.domain.model.*;
import com.galactic.starport.domain.port.OutboxPort;
import com.galactic.starport.domain.port.StarportGateway;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Service responsible for persisting and updating reservations. Each method in this class
 * is transactional and should be invoked from another bean (e.g., ReservationService)
 * so that Spring's proxy can intercept the calls. Do not call these methods from
 * within the same class; instead, inject this service where needed.
 */
@Service
@RequiredArgsConstructor
public class ReservationPersistenceService {

    private final StarportGateway starportGateway;
    private final OutboxPort outboxPort;
    private final ObjectMapper objectMapper;
    private final ReservationEventMapper reservationEventMapper;

    /**
     * Allocate a docking bay and create a reservation in HOLD state.
     * <p>
     * This operation performs all database interactions within its own
     * transaction to avoid holding locks while talking to remote services. The
     * caller is responsible for performing any follow‑up actions (such as
     * route planning) outside of the transactional boundary and then calling
     * {@link #confirmReservation} to finalize the booking.
     */
    @Transactional
    public Reservation allocateHold(ReserveBayCommand command) {
        var range = new TimeRange(command.startAt(), command.endAt());

        // load the starport definition
        final Starport starport;
        try {
            starport = starportGateway
                    .findByCode(command.starportCode())
                    .orElseThrow(() -> new StarportNotFoundException(
                            "Starport %s not found".formatted(command.starportCode())));
        } catch (DataAccessException dae) {
            throw new RepositoryUnavailableException("Database error while loading starport", dae);
        }

        // find the first free bay for the given time range
        final DockingBay freeBay;
        try {
            freeBay = starportGateway
                    .findFirstFreeBay(starport.getCode(), command.shipClass(), range.getStartAt(), range.getEndAt())
                    .orElseThrow(() -> new NoDockingBaysAvailableException(
                            starport.getCode(), range.getStartAt(), range.getEndAt()));
        } catch (DataAccessException dae) {
            throw new RepositoryUnavailableException("Database error while searching free bay", dae);
        }

        // create a reservation in HOLD state without computing the final fee yet
        Reservation hold = Reservation.builder()
                .dockingBay(freeBay)
                .shipId(command.shipId())
                .shipClass(command.shipClass())
                .period(range)
                .status(ReservationStatus.HOLD)
                .build();

        try {
            return starportGateway.save(hold);
        } catch (DataAccessException dae) {
            throw new RepositoryUnavailableException("Database error while saving reservation", dae);
        }
    }

    /**
     * Finalize the reservation by computing the tariff (including any risk
     * adjustments) and persisting it as CONFIRMED. Also writes the outbox event in the
     * same transaction so that either both the reservation and the event are
     * persisted or neither is.
     */
    @Transactional
    public Reservation confirmReservation(Reservation hold, Route route, BigDecimal fee) {
        hold.confirmReservation(route, fee);
        final Reservation saved;
        try {
            saved = starportGateway.save(hold);
        } catch (DataAccessException dae) {
            throw new RepositoryUnavailableException("Database error while saving reservation", dae);
        }

        // build and persist the event payload
        var payload = reservationEventMapper.toReservationCreated(saved);
        String json;
        try {
            json = objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize event payload", e);
        }

        outboxPort.save(
                "ReservationCreated",
                "reservationCreated-out-0",
                saved.getDockingBay().getStarport().getId().toString(),
                json,
                Map.of(
                        "partitionKey",
                        saved.getDockingBay().getStarport().getId().toString(),
                        "contentType",
                        "application/json"));

        return saved;
    }

    /**
     * Release a previously held reservation. This is a compensating transaction
     * executed when a downstream operation (such as route planning) fails. The
     * reservation status is updated to CANCELLED in its own transaction.
     */
    @Transactional
    public void releaseHold(Reservation reservation) {
        reservation.setStatus(ReservationStatus.CANCELLED);
        try {
            starportGateway.save(reservation);
        } catch (DataAccessException dae) {
            throw new RepositoryUnavailableException("Database error while releasing hold", dae);
        }
    }
}