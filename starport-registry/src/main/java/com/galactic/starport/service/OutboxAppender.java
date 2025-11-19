package com.galactic.starport.service;

import java.util.HashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
class OutboxAppender {

    private final OutboxWriter outboxWriter;

    @Value("${app.bindings.reservations.name:reservations-out}")
    String reservationsBinding;

    void appendReservationEvent(String eventType, Reservation reservation, Route routeOrNull) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("reservationId", reservation.getId());
        payload.put("fee", reservation.getFeeCharged());
        if (routeOrNull != null) {
            payload.put("routeCode", routeOrNull.getRouteCode());
            payload.put("riskScore", routeOrNull.getRiskScore());
        }

        Map<String, Object> headers = Map.of("contentType", "application/json");

        outboxWriter.append(reservationsBinding, eventType, String.valueOf(reservation.getId()), payload, headers);
    }
}
