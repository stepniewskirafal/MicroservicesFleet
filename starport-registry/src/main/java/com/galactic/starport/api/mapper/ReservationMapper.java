package com.galactic.starport.api.mapper;

import com.galactic.starport.api.dto.ReservationCreateRequest;
import com.galactic.starport.api.dto.ReservationResponse;
import com.galactic.starport.application.command.ReserveBayCommand;
import com.galactic.starport.domain.model.Reservation;
import java.util.UUID;
import org.mapstruct.*;

@Mapper(
        componentModel = "spring",
        injectionStrategy = InjectionStrategy.CONSTRUCTOR,
        unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface ReservationMapper {

    @Mapping(target = "starportCode", source = "code")
    @Mapping(target = "shipId", source = "req.shipId")
    @Mapping(target = "shipClass", source = "req.shipClass")
    @Mapping(target = "startAt", source = "req.startAt")
    @Mapping(target = "endAt", source = "req.endAt")
    @Mapping(target = "requestRoute", source = "req.requestRoute")
    ReserveBayCommand toCommand(String code, ReservationCreateRequest req);

    default ReservationResponse toResponse(Reservation r) {
        if (r == null) return null;
        return new ReservationResponse(
                r.getId(),
                r.getDockingBay().getStarport().getCode(),
                r.getDockingBay().getId(),
                r.getStartAt(),
                r.getEndAt(),
                r.getFeeAmount(),
                UUID.randomUUID().toString() // r.getRouteId()
                );
    }
}
