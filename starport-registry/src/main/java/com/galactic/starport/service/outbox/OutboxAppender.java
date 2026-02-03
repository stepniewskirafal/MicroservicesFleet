package com.galactic.starport.service.outbox;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.galactic.starport.service.Reservation;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.transport.Kind;
import io.micrometer.observation.transport.SenderContext;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import java.util.HashMap;
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
    private final ObservationRegistry observationRegistry;
    private final Tracer tracer;
    private final Propagator propagator;

    @Value("${app.events.topics.reservations}")
    String reservationsBinding;

    void publishReservationConfirmedEvent(Reservation reservation) {
        SenderContext<Map<String, Object>> senderContext = new SenderContext<>(Map::put, Kind.PRODUCER);
        senderContext.setRemoteServiceName("kafka");
        Map<String, Object> headers = new HashMap<>();
        senderContext.setCarrier(headers);

        Observation.createNotStarted("reservations.outbox.append", () -> senderContext, observationRegistry )
                .lowCardinalityKeyValue("binding", reservationsBinding)
                .lowCardinalityKeyValue("eventType", EventType.RESERVATION_CONFIRMED.eventName)
                .highCardinalityKeyValue("reservationId", String.valueOf(reservation.getId()))
                .observe(() -> {
                    ReservationEventPayload dto = mapper.toPayload(reservation);
                    Map<String, Object> payload = objectMapper.convertValue(dto, new TypeReference<>() {});

                    outboxWriter.save(
                            reservationsBinding,
                            EventType.RESERVATION_CONFIRMED.eventName,
                            String.valueOf(reservation.getId()),
                            payload,
                            headers);
                });
    }

    enum EventType {
        RESERVATION_CONFIRMED("ReservationConfirmed");
        final String eventName;

        EventType(String eventName) {
            this.eventName = eventName;
        }
    }
}
