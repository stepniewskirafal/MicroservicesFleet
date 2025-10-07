package com.galactic.starport.application.event;

import com.galactic.starport.domain.event.ReservationCreated;
import com.galactic.starport.domain.model.Reservation;

import java.time.Instant;
import java.util.UUID;

public class ReservationEventMapper {

    public static ReservationCreated toReservationCreated(Reservation r) {
        return new ReservationCreated(
                /* eventId       */ UUID.randomUUID().toString(),
                /* occurredAt    */ Instant.now(),              // albo r.getCreatedAt() jeśli wolisz czas domenowy
                /* starportCode  */ r.getDockingBay().getStarport().getCode(),
                /* reservationId */ r.getId().toString(),
                /* bayNumber     */ r.getDockingBay().getId().toString(), // jeśli masz osobne "number", użyj go tutaj
                /* shipId        */ r.getShipId(),
                /* shipClass     */ r.getShipClass().name(),
                /* startAt       */ r.getPeriod().getStartAt(),
                /* endAt         */ r.getPeriod().getEndAt(),
                /* feeCharged    */ r.getFeeAmount() == null ? null : r.getFeeAmount().toPlainString()
        );
    }
}
