package com.galactic.starport.controller;

import com.galactic.starport.service.Reservation;
import com.galactic.starport.service.ReserveBayCommand;
import java.util.Optional;
import org.springframework.stereotype.Component;

@Component
class ReservationWebMapper {

    ReserveBayCommand toCommand(String code, ReservationCreateRequest req) {
        return ReserveBayCommand.builder()
                .startStarportCode(req.originPortId())
                .destinationStarportCode(code)
                .customerCode(req.customerCode())
                .shipCode(req.shipCode())
                .shipClass(ReserveBayCommand.ShipClass.valueOf(req.shipClass().name()))
                .startAt(req.startAt())
                .endAt(req.endAt())
                .requestRoute(req.requestRoute())
                .build();
    }

    ReservationResponse toResponse(String starportCode, Reservation reservation) {
        ReservationResponse.Route route = Optional.ofNullable(reservation.getRoute())
                .map(r -> ReservationResponse.Route.builder()
                        .routeCode(r.getRouteCode())
                        .startStarportCode(r.getStartStarportCode())
                        .destinationStarportCode(r.getDestinationStarportCode())
                        .etaLightYears(r.getEtaLightYears())
                        .riskScore(r.getRiskScore())
                        .build())
                .orElse(null);

        return ReservationResponse.builder()
                .reservationId(reservation.getId())
                .starportCode(starportCode)
                .bayNumber(reservation.getDockingBay().getBayLabel())
                .startAt(reservation.getStartAt())
                .endAt(reservation.getEndAt())
                .feeCharged(reservation.getFeeCharged())
                .route(route)
                .build();
    }
}
