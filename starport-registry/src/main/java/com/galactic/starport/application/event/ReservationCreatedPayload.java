package com.galactic.starport.application.event;

/** Lekki payload publikowany na Kafka (warstwa Application). */
public record ReservationCreatedPayload(
        String reservationId,
        String starportId,
        String bayId,
        String shipId,
        String shipClass,
        String from, // ISO-8601
        String to // ISO-8601
        ) {}
