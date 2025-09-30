package com.galactic.starport.domain.port;

import java.util.Map;

/**
 * Port domenowy do trwałego zapisu zdarzeń do Outboxa.
 * Przyjmuje GOTOWY JSON payload + nagłówki.
 * (Dzięki temu Domain nie widzi klas z Application.)
 */
public interface OutboxPort {
    void save(
            String eventType,
            String binding, // np. "reservationCreated-out-0"
            String messageKey, // np. starportId
            String payloadJson, // już zserializowany JSON
            Map<String, Object> headers);
}
