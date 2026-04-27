package com.galactic.starport.service.outbox;

import java.math.BigDecimal;
import java.time.Instant;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
class ReservationEventPayload {
    private Long reservationId;
    private String status;
    private String starportCode;
    private String dockingBayLabel;
    private String customerCode;
    private String shipCode;
    private String routeCode;
    private Instant startAt;
    private Instant endAt;
    private BigDecimal feeCharged;
    /**
     * Timestamp when this event was produced (right before INSERT into event_outbox). Used downstream
     * by telemetry-pipeline to compute end-to-end async lag (events.reservation.lag.seconds).
     */
    private Instant producedAt;
}
