package com.galactic.starport.domain.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Zdarzenie domenowe informujące, że próba wyznaczenia trasy zakończyła się niepowodzeniem.
 * `reservationId` pozwala powiązać zdarzenie z konkretną rezerwacją w usłudze Starport Registry.
 * Pole `reason` może zawierać komunikat o przyczynie odrzucenia (np. „brak paliwa”, „niebezpieczny szlak” itp.).
 */
public record RouteRejectedEvent(String eventId, Instant occurredAt, UUID reservationId, String reason) {}
