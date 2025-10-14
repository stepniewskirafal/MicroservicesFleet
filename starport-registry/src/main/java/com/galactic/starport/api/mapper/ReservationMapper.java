package com.galactic.starport.api.mapper;

import com.galactic.starport.api.dto.ReservationCreateRequest;
import com.galactic.starport.api.dto.ReservationResponse;
import com.galactic.starport.application.command.ReserveBayCommand;
import com.galactic.starport.domain.model.Reservation;
import org.mapstruct.InjectionStrategy;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;

@Mapper(
        componentModel = "spring",
        injectionStrategy = InjectionStrategy.CONSTRUCTOR,
        unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface ReservationMapper {

    // --- API -> Command ---
    @Mapping(target = "starportCode", source = "code")
    @Mapping(target = "shipId", source = "req.shipId")
    @Mapping(target = "shipClass", source = "req.shipClass")
    @Mapping(target = "startAt", source = "req.startAt")
    @Mapping(target = "endAt", source = "req.endAt")
    @Mapping(target = "requestRoute", source = "req.requestRoute")
    ReserveBayCommand toCommand(String code, ReservationCreateRequest req);

    @Mapping(target = "reservationId", source = "id")
    @Mapping(target = "starportCode", source = "dockingBay.starport.code")
    @Mapping(target = "bayNumber", source = "dockingBay.id")
    @Mapping(target = "startAt", expression = "java(r.getStartAt())")
    @Mapping(target = "endAt", expression = "java(r.getEndAt())")
    @Mapping(target = "feeCharged", source = "feeAmount")
    @Mapping(target = "routeId", expression = "java(java.util.UUID.randomUUID().toString())")
    ReservationResponse toResponse(Reservation r);
}
