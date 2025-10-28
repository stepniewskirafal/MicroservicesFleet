package com.galactic.starport.controller;

import com.galactic.starport.domain.Reservation;
import com.galactic.starport.domain.ReserveBayCommand;
import com.galactic.starport.domain.Route;
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

    ReservationResponse toResponse(String starportCode, Reservation r) {
        return ReservationResponse.builder()
                .reservationId(r.getId())
                .starportCode(starportCode)
                .bayNumber(r.getDockingBay().getBayLabel())
                .startAt(r.getStartAt())
                .endAt(r.getEndAt())
                .feeCharged(r.getFeeCharged())
                .route(r.getRoutes().stream()
                        .filter(Route::isActive)
                        .findFirst()
                        .map(route -> ReservationResponse.Route.builder()
                                .routeCode(route.getRouteCode())
                                .startStarportCode(route.getStartStarportCode())
                                .destinationStarportCode(route.getDestinationStarportCode())
                                .etaLightYears(route.getEtaLightYears())
                                .riskScore(route.getRiskScore())
                                .build())
                        .orElse(null))
                .build();
    }
}
