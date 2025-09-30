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

    @Mapping(target = "starportCode", source = "code")
    ReserveBayCommand toCommand(String code, ReservationCreateRequest req);

    @Mapping(target = "reservationId", source = "id")
    @Mapping(target = "starportCode", source = "dockingBay.starport.code")
    @Mapping(target = "bayNumber", source = "dockingBay.id")
    @Mapping(target = "startAt", expression = "java(r.getStartAt())")
    @Mapping(target = "endAt", expression = "java(r.getEndAt())")
    @Mapping(target = "feeCharged", source = "feeAmount")
    @Mapping(target = "routeId", expression = "java(selectActiveRoute(r))")
    ReservationResponse toResponse(Reservation r);

    default ReservationResponse.Route selectActiveRoute(Reservation r) {
        if (r == null || r.getRoutes() == null || r.getRoutes().isEmpty()) {
            return null;
        }
        return r.getRoutes().stream()
                .filter(com.galactic.starport.domain.model.Route::isActive)
                .findFirst()
                .map(activeRoute -> new ReservationResponse.Route(
                        activeRoute.getId(), activeRoute.getEtaLY(), activeRoute.getRiskScore()))
                .orElse(null);
    }
}
