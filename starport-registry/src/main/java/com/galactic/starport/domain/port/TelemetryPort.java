package com.galactic.starport.domain.port;

import com.galactic.starport.domain.event.IncidentRecorded;
import com.galactic.starport.domain.enums.ShipClass;
import com.galactic.starport.domain.model.Reservation;
import java.math.BigDecimal;

public interface TelemetryPort {
    void reservationCreated(Reservation r);

    void tariffCalculated(
            String starportCode,
            java.util.UUID reservationId,
            ShipClass shipClass,
            long durationHours,
            BigDecimal amount);

    void incidentRecorded(IncidentRecorded incident);
}
