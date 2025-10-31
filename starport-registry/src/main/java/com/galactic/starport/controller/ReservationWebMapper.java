package com.galactic.starport.controller;

import com.galactic.starport.service.Reservation;
import com.galactic.starport.service.ReserveBayCommand;
import com.galactic.starport.service.Route;
import java.util.Collection;
import java.util.stream.Stream;
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

        final ReservationResponse.Route activeRoute = Stream.ofNullable(reservation.getRoutes())
                .flatMap(Collection::stream)
                .filter(Route::isActive)
                .findFirst()
                .map(route -> ReservationResponse.Route.builder()
                        .routeCode(route.getRouteCode())
                        .startStarportCode(route.getStartStarportCode())
                        .destinationStarportCode(route.getDestinationStarportCode())
                        .etaLightYears(route.getEtaLightYears())
                        .riskScore(route.getRiskScore())
                        .build())
                .orElse(null);

        return ReservationResponse.builder()
                .reservationId(reservation.getId())
                .starportCode(starportCode)
                .bayNumber(reservation.getDockingBay().getBayLabel())
                .startAt(reservation.getStartAt())
                .endAt(reservation.getEndAt())
                .feeCharged(reservation.getFeeCharged())
                .route(activeRoute)
                .build();
    }
}
