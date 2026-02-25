package com.galactic.starport.service.outbox;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
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
    @JsonSerialize(using = ToStringSerializer.class)
    private BigDecimal feeCharged;
}
