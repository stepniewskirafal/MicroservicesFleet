package com.galactic.starport.controller;

import com.galactic.starport.service.Reservation;
import com.galactic.starport.service.ReserveBayCommand;
import org.springframework.stereotype.Component;

@Component
class ReservationWebMapper {

    ReserveBayCommand toCommand(String code, ReservationCreateRequest req) {
        return ReserveBayCommand.builder()
                .starportCode(code)
                .shipId(req.shipId())
                .shipClass(ReserveBayCommand.ShipClass.valueOf(req.shipClass().name()))
                .startAt(req.startAt())
                .endAt(req.endAt())
                .requestRoute(req.requestRoute())
                .originPortId(req.originPortId())
                .build();
    }

    ReservationResponse toResponse(String starportCode, Reservation r) {
        return ReservationResponse.builder()
                .reservationId(r.getId())
                .starportCode(starportCode)
                .bayNumber(r.getDockingBayId())
                .startAt(r.getStartAt())
                .endAt(r.getEndAt())
                .feeCharged(r.getFeeCharged())
                // Route is not yet implemented on domain; keep null / extend when available
                .route(null)
                .build();
    }
}
