package com.galactic.starport.infrastructure;

import com.galactic.starport.application.event.IncidentRecorded;
import com.galactic.starport.application.event.StarportReservationCreated;
import com.galactic.starport.application.event.TariffCalculated;
import com.galactic.starport.domain.port.TelemetryPort;
import com.galactic.starport.domain.enums.ShipClass;
import com.galactic.starport.domain.model.Reservation;
import com.galactic.starport.infrastructure.config.TopicsProperties;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "telemetry.adapter", havingValue = "stream", matchIfMissing = true)
public class CloudStreamTelemetryAdapter implements TelemetryPort {

    private final TopicsProperties topicsProperties;
    private final StreamBridge bridge;

    @Override
    public void reservationCreated(Reservation r) {
        var evt = new StarportReservationCreated(
                UUID.randomUUID().toString(),
                Instant.now(),
                r.getDockingBay().getStarport().getCode(),
                r.getId(),
                r.getDockingBay().getId(),
                r.getShipId(),
                r.getShipClass(),
                r.getStartAt(),
                r.getEndAt(),
                r.getFeeAmount());
        // bridge.send(topicsProperties.reservations() , evt);
    }

    @Override
    public void tariffCalculated(
            String starportCode, UUID reservationId, ShipClass shipClass, long durationHours, BigDecimal amount) {
        var evt = new TariffCalculated(
                UUID.randomUUID().toString(),
                Instant.now(),
                starportCode,
                reservationId,
                shipClass,
                durationHours,
                amount);
        // bridge.send(topicsProperties.tariffs(), evt);
    }

    @Override
    public void incidentRecorded(IncidentRecorded incident) {
        // bridge.send(topicsProperties.incidents(), incident);
    }
}
