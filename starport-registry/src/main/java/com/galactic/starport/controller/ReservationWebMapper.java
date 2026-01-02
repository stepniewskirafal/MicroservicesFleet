package com.galactic.starport.controller;

import com.galactic.starport.service.Reservation;
import com.galactic.starport.service.ReserveBayCommand;
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
        final ReservationResponse.Route route = ReservationResponse.Route.builder()
                .routeCode(reservation.getRoute().getRouteCode())
                .startStarportCode(reservation.getRoute().getStartStarportCode())
                .destinationStarportCode(reservation.getRoute().getDestinationStarportCode())
                .etaLightYears(reservation.getRoute().getEtaLightYears())
                .riskScore(reservation.getRoute().getRiskScore())
                .build();

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
