package com.galactic.starport.service.outbox;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.galactic.starport.service.Reservation;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
class OutboxAppender {

    private final OutboxWriter outboxWriter;
    private final ObjectMapper objectMapper;
    private final ReservationEventMapper mapper;

    @Value("${app.bindings.reservations.name:reservations-out}")
    String reservationsBinding;

    void publishReservationConfirmedEvent(Reservation reservation) {
        ReservationEventPayload dto = mapper.toPayload(reservation);

        Map<String, Object> payload = objectMapper.convertValue(dto, new TypeReference<Map<String, Object>>() {});

        Map<String, Object> headers = Map.of("contentType", "application/json");

        outboxWriter.save(
                reservationsBinding,
                String.valueOf(reservation.getId()),
                eventType.RESERVATION_CONFIRMED.name,
                payload,
                headers);
    }

    enum eventType {
        RESERVATION_CONFIRMED("ReservationConfirmed");
        private final String name;

        eventType(String name) {
            this.name = name;
        }
    }
}
