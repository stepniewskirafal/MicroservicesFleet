package com.galactic.starport.controller;

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
}
