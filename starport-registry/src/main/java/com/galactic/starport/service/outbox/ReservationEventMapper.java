package com.galactic.starport.service.outbox;

import com.galactic.starport.service.Reservation;
import org.springframework.stereotype.Component;

@Component
class ReservationEventMapper {
    ReservationEventPayload toPayload(Reservation r) {
        return ReservationEventPayload.builder()
                .reservationId(r.getId())
                .status(r.getStatus().name())
                .starportCode(r.getStarport() != null ? r.getStarport().getCode() : null)
                .dockingBayLabel(r.getDockingBay() != null ? r.getDockingBay().getBayLabel() : null)
                .customerCode(r.getCustomer() != null ? r.getCustomer().getCustomerCode() : null)
                .shipCode(r.getShip() != null ? r.getShip().getShipCode() : null)
                .routeCode(r.getRoute() != null ? r.getRoute().getRouteCode() : null)
                .startAt(r.getStartAt())
                .endAt(r.getEndAt())
                .feeCharged(r.getFeeCharged())
                .build();
    }
}
