package com.galactic.starport.repository.mapper;

import com.galactic.starport.domain.Reservation;
import com.galactic.starport.domain.Route;
import com.galactic.starport.repository.RouteEntity;

public final class RouteMapper {

    private RouteMapper() {}

    public static Route toDomain(RouteEntity entity, Reservation reservation) {
        if (entity == null) {
            return null;
        }

        return Route.builder()
                .id(entity.getId())
                .reservation(reservation)
                .routeCode(entity.getRouteCode())
                .startStarportCode(entity.getStartStarportCode())
                .destinationStarportCode(entity.getDestinationStarportCode())
                .etaLightYears(entity.getEtaLightYears())
                .riskScore(entity.getRiskScore())
                .isActive(true)
                .build();
    }

    public static RouteEntity toEntity(Route route) {
        if (route == null) {
            return null;
        }

        RouteEntity entity = new RouteEntity();
        entity.setId(route.getId());
        entity.setRouteCode(route.getRouteCode());
        entity.setStartStarportCode(route.getStartStarportCode());
        entity.setDestinationStarportCode(route.getDestinationStarportCode());
        entity.setEtaLightYears(route.getEtaLightYears());
        entity.setRiskScore(route.getRiskScore());
        return entity;
    }
}
