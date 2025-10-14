package com.galactic.starport.application.event;

import com.galactic.starport.domain.event.ReservationCreated;
import com.galactic.starport.domain.model.Reservation;
import org.mapstruct.*;

@Mapper(
        componentModel = "spring",
        injectionStrategy = InjectionStrategy.CONSTRUCTOR,
        unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface ReservationEventMapper {
    @Mapping(target = "eventId", expression = "java(java.util.UUID.randomUUID().toString())")
    @Mapping(target = "occurredAt", expression = "java(java.time.Instant.now())")
    @Mapping(target = "starportCode", expression = "java(r.getDockingBay().getStarport().getCode())")
    @Mapping(target = "reservationId", expression = "java(r.getId().toString())")
    @Mapping(target = "bayNumber", expression = "java(r.getDockingBay().getId().toString())")
    @Mapping(target = "shipId", source = "shipId")
    @Mapping(target = "shipClass", source = "shipClass")
    @Mapping(target = "startAt", expression = "java(r.getStartAt())")
    @Mapping(target = "endAt", expression = "java(r.getEndAt())")
    @Mapping(target = "feeCharged", source = "feeAmount")
    ReservationCreated toReservationCreated(Reservation r);
}
